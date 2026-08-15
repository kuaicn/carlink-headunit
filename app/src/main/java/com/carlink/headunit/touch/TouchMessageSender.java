package com.carlink.headunit.touch;

import android.util.Log;

import com.carlink.headunit.net.Protocol;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Serializes control messages onto the control socket from a dedicated thread, so the UI
 * thread never performs network I/O. Messages are queued in FIFO order; a write failure is
 * reported once through {@link ErrorListener} and stops the sender (a failure surfacing only
 * after {@link #stop} was requested is expected teardown fallout and is not reported).
 * <p>
 * The queue is bounded ({@link #MAX_QUEUED_MESSAGES}) so a phone that stops reading (slow or
 * dead network) cannot pile up MOVE events without limit — that would grow memory and, worse,
 * keep replaying stale motion long after the finger moved. See {@link #send} for the overflow
 * policy.
 */
public final class TouchMessageSender {

    private static final String TAG = "CarLinkHeadunit";

    public interface ErrorListener {
        /** Called on the sender thread when writing to the control socket fails. */
        void onSendError(IOException e);
    }

    private static final long JOIN_TIMEOUT_MS = 500;

    /** Bounded backlog: 256 x 32-byte touch messages = 8 KiB worst case. */
    private static final int MAX_QUEUED_MESSAGES = 256;

    private final OutputStream out;
    private final ErrorListener errorListener;
    private final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(MAX_QUEUED_MESSAGES);

    private Thread thread;
    private volatile boolean running;

    public TouchMessageSender(OutputStream out, ErrorListener errorListener) {
        this.out = out;
        this.errorListener = errorListener;
    }

    public void start() {
        running = true;
        thread = new Thread(this::loop, "carlink-control-sender");
        thread.start();
    }

    /**
     * Queue one message; silently dropped once the sender is stopped. Safe from any thread.
     * <p>
     * Overflow policy: queued MOVEs are shed first — touch positions are absolute, so a stale
     * MOVE is always superseded by a newer event, and removing only MOVEs keeps the
     * DOWN -&gt; MOVE* -&gt; UP order of every pointer intact. DOWN/UP and back-key messages are
     * never shed (a lost UP would leak the pointer state server-side). If the queue is still
     * full after dropping every MOVE, it holds hundreds of undrained DOWN/UP messages and the
     * connection is effectively dead, so the message is dropped rather than blocking the
     * caller (the next socket write will fail and tear the session down anyway).
     */
    public void send(byte[] message) {
        if (!running) {
            return;
        }
        if (!queue.offer(message)) {
            queue.removeIf(TouchMessageSender::isMove);
            queue.offer(message);
        }
    }

    /** Queue several messages preserving their order. Safe from any thread. */
    public void sendAll(List<byte[]> messages) {
        // Go through send() so the overflow policy applies per message (a bare addAll on a
        // bounded queue would throw IllegalStateException once full)
        for (byte[] message : messages) {
            send(message);
        }
    }

    /** True for a serialized touch MOVE message — the only kind safe to shed (absolute positions). */
    private static boolean isMove(byte[] message) {
        return message.length == Protocol.TOUCH_MESSAGE_SIZE && message[0] == Protocol.TYPE_INJECT_TOUCH_EVENT
                && message[1] == Protocol.ACTION_MOVE;
    }

    private void loop() {
        while (running) {
            final byte[] message;
            try {
                message = queue.take();
            } catch (InterruptedException e) {
                break;
            }
            try {
                out.write(message);
                out.flush();
            } catch (IOException e) {
                // Only report while running: a write failure after stop() is caused by our own
                // teardown (the session thread closes the sockets right after stopping us), and
                // reporting it would race the real disconnect reason and mask it
                if (running) {
                    running = false;
                    Log.w(TAG, "control channel write failed", e);
                    errorListener.onSendError(e);
                }
                break;
            }
        }
    }

    /**
     * Stop the sender thread (interrupt + bounded join). A thread blocked in a socket write
     * is additionally released by closing the session sockets.
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
        // Anything still unsent belongs to the session that just ended: release it
        queue.clear();
    }
}
