package com.carlink.headunit.net;

import android.os.SystemClock;
import android.util.Log;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads the phone -&gt; car direction of the control channel (device messages), which used to
 * be write-only on this client. Parses just enough of each message to stay in sync with the
 * stream (see carlink-protocol.md "device 消息"): CLIPBOARD (type 0: u32 length + UTF-8 text)
 * and ACK_CLIPBOARD (type 1: s64) are skipped, HEARTBEAT (type 3: the type byte alone) only
 * arms the phone-death detection (see {@link #isHeartbeatSeen()}); payloads are never
 * interpreted (clipboard sync is not implemented), so a misparse is harmless by construction.
 * <p>
 * An unknown type byte cannot be length-delimited, so the only safe resync strategy is to
 * discard just that byte and retry parsing at the next one; the stream then self-heals on one
 * of the following heartbeats (a single 0x03 byte every ~10s — a false CLIPBOARD/ACK sync may
 * skip past one, but bytes consumed while resyncing still feed the liveness timestamp).
 * <p>
 * Mirrors {@code TouchMessageSender}: a dedicated thread does the blocking reads, a failure is
 * reported once through {@link ErrorListener} (a failure surfacing after {@link #stop} is
 * expected teardown fallout and is not reported).
 */
public final class DeviceMessageReader {

    private static final String TAG = "CarLinkHeadunit";

    private static final long JOIN_TIMEOUT_MS = 500;

    /** Sanity bound for a CLIPBOARD length: the phone never exceeds its own 256 KiB message cap. */
    private static final int MAX_TEXT_LENGTH = 256 * 1024;

    public interface ErrorListener {
        /** Called on the reader thread when reading the control channel fails (or hits EOF). */
        void onReadError(IOException e);
    }

    private final InputStream in;
    private final ErrorListener errorListener;
    private final byte[] buffer = new byte[8192];

    private Thread thread;
    private volatile boolean running;

    /** Last control-channel byte received (uptimeMillis); 0 until the first message. Refreshed
     * on every byte read, including skipped payload (see {@link #skipClipboard()}). */
    private volatile long lastRxTime;
    /** Sticky once a heartbeat was seen; only a heartbeat-capable (new) phone ever sends one. */
    private volatile boolean heartbeatSeen;

    public DeviceMessageReader(InputStream in, ErrorListener errorListener) {
        this.in = in;
        this.errorListener = errorListener;
    }

    public void start() {
        running = true;
        thread = new Thread(this::loop, "carlink-control-reader");
        thread.start();
    }

    public long getLastRxTime() {
        return lastRxTime;
    }

    public boolean isHeartbeatSeen() {
        return heartbeatSeen;
    }

    private void loop() {
        try {
            while (running) {
                int type = in.read();
                if (type < 0) {
                    throw new EOFException("phone closed the control channel");
                }
                lastRxTime = SystemClock.uptimeMillis();
                switch (type) {
                    case Protocol.DEVICE_TYPE_CLIPBOARD:
                        skipClipboard();
                        break;
                    case Protocol.DEVICE_TYPE_ACK_CLIPBOARD:
                        Protocol.readFully(in, buffer, 0, 8);
                        break;
                    case Protocol.DEVICE_TYPE_HEARTBEAT:
                        if (!heartbeatSeen) {
                            heartbeatSeen = true;
                            Log.i(TAG, "first heartbeat received: phone speaks the liveness protocol");
                        }
                        break;
                    default:
                        // Unknown type: the payload length is unknowable, so discard this single
                        // byte and resync at the next one (a heartbeat byte arrives every ~10s)
                        Log.w(TAG, "unknown device message type " + type + ", discarding 1 byte");
                        break;
                }
            }
        } catch (IOException e) {
            // Only report while running: a read failure after stop() is caused by our own
            // teardown (the session is closed right after stopping us), and reporting it
            // would race the real disconnect reason and mask it
            if (running) {
                running = false;
                Log.w(TAG, "control channel read failed", e);
                errorListener.onReadError(e);
            }
        }
    }

    /** Consume a CLIPBOARD payload (u32 length + UTF-8 text, discarded). */
    private void skipClipboard() throws IOException {
        Protocol.readFully(in, buffer, 0, 4);
        int length = Protocol.readIntBE(buffer, 0);
        if (length < 0 || length > MAX_TEXT_LENGTH) {
            // Beyond what the phone ever sends: the stream is desynchronized. Skipping a bogus
            // distance could swallow minutes of heartbeats, so fall back to byte-wise resync
            Log.w(TAG, "implausible clipboard length " + length + ", resyncing byte-wise");
            return;
        }
        int remaining = length;
        while (remaining > 0) {
            int count = in.read(buffer, 0, Math.min(remaining, buffer.length));
            if (count < 0) {
                throw new EOFException("phone closed the control channel");
            }
            // Payload bytes are signs of life too: a resync false positive can park the parser
            // in this skip for a long time, and heartbeats swallowed as payload must still feed
            // the phone-death detection
            lastRxTime = SystemClock.uptimeMillis();
            remaining -= count;
        }
    }

    /**
     * Stop the reader thread (interrupt + bounded join). A thread blocked in a socket read is
     * additionally released by closing the session sockets.
     */
    public void stop() {
        running = false;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }
}
