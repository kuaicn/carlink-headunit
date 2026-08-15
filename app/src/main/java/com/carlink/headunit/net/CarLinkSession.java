package com.carlink.headunit.net;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;

/**
 * Owns the two TCP channels of a CarLink session and performs the JSON handshake.
 * <p>
 * Sequence (see carlink-protocol.md):
 * <ol>
 *   <li>{@link #connect}: TCP to the phone control port, send {@code hello}, receive {@code ready};</li>
 *   <li>{@link #connectVideo}: TCP to the video port from {@code ready};</li>
 *   <li>the control channel then carries scrcpy control messages (car -&gt; phone) plus device
 *       messages (phone -&gt; car: clipboard, heartbeat — consumed by {@code DeviceMessageReader});
 *       the video channel carries the video stream (phone -&gt; car).</li>
 * </ol>
 * All methods are blocking and must be called from a worker thread, except {@link #close()}
 * which is safe to call from any thread (it unblocks pending I/O).
 */
public final class CarLinkSession {

    /**
     * No video data for this long triggers a control-channel liveness probe. A static screen
     * streams nothing for an unbounded time (the phone's encoder blocks until the virtual
     * display changes), so a stalled video read is normal and must not kill the session.
     */
    private static final int VIDEO_STALL_PROBE_MS = 5000;

    /** Successful handshake result. */
    public static final class Ready {
        /** Codec selected by the phone: "h264" or "h265". */
        public final String codec;
        /** TCP port of the video channel. */
        public final int videoPort;

        Ready(String codec, int videoPort) {
            this.codec = codec;
            this.videoPort = videoPort;
        }
    }

    private Socket controlSocket;
    private Socket videoSocket;
    private OutputStream controlOut;
    private InputStream controlIn;
    private InputStream videoIn;
    private boolean closed;

    /**
     * Connect the control channel and perform the handshake.
     *
     * @param width/height/dpi the car screen physical parameters; the phone creates its
     *                         VirtualDisplay (and the encoder) with exactly these values
     * @param codecs           locally supported video codecs, in preference order ("h264"/"h265")
     */
    public Ready connect(String host, int port, int width, int height, int dpi, List<String> codecs, int timeoutMs)
            throws IOException {
        synchronized (this) {
            if (closed) {
                throw new IOException("Session already closed");
            }
            controlSocket = new Socket();
        }
        try {
            controlSocket.setTcpNoDelay(true);
            controlSocket.connect(new InetSocketAddress(host, port), timeoutMs);
        } catch (IOException | RuntimeException e) {
            // A failed connect leaves the socket open: close it (and end the session)
            close();
            throw e;
        }
        try {
            OutputStream out = controlSocket.getOutputStream();
            InputStream in = controlSocket.getInputStream();
            // The handshake must not block forever; reset to blocking mode afterwards.
            controlSocket.setSoTimeout(timeoutMs);

            JSONObject hello = new JSONObject();
            hello.put("type", "hello");
            hello.put("width", width);
            hello.put("height", height);
            hello.put("dpi", dpi);
            hello.put("codecs", new JSONArray(codecs));
            Protocol.writeHandshakeFrame(out, hello.toString());

            String response = Protocol.readHandshakeFrame(in);
            controlSocket.setSoTimeout(0);
            if (response == null) {
                // The phone closes the connection without a frame when the handshake is refused
                throw new IOException("Handshake refused by the phone (connection closed)");
            }

            final JSONObject json;
            try {
                json = new JSONObject(response);
            } catch (JSONException e) {
                throw new IOException("Malformed handshake response: " + response);
            }
            String type = json.optString("type");
            if ("error".equals(type)) {
                throw new IOException("Handshake rejected: " + json.optString("reason", "unknown reason"));
            }
            if (!"ready".equals(type)) {
                throw new IOException("Unexpected handshake response: " + response);
            }
            String codec;
            int videoPort;
            try {
                codec = json.getString("codec");
                videoPort = json.getInt("videoPort");
            } catch (JSONException e) {
                throw new IOException("Incomplete ready message: " + response);
            }
            if (videoPort <= 0 || videoPort > 65535) {
                throw new IOException("Invalid videoPort in ready message: " + videoPort);
            }
            controlOut = out;
            controlIn = in;
            return new Ready(codec, videoPort);
        } catch (JSONException e) {
            close();
            // JSONException is checked in Android's org.json; building the hello message with
            // fixed keys cannot actually fail, but a malformed response must close the session
            throw new IOException("Handshake failed: malformed JSON", e);
        } catch (SocketTimeoutException e) {
            close();
            throw new IOException("Handshake timed out after " + timeoutMs + " ms", e);
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    /** Connect the video channel to the port given by the handshake. */
    public void connectVideo(String host, int videoPort, int timeoutMs) throws IOException {
        synchronized (this) {
            if (closed) {
                throw new IOException("Session already closed");
            }
            videoSocket = new Socket();
        }
        try {
            videoSocket.setTcpNoDelay(true);
            videoSocket.connect(new InetSocketAddress(host, videoPort), timeoutMs);
            videoSocket.setSoTimeout(VIDEO_STALL_PROBE_MS);
            videoIn = new StallProbeInputStream(videoSocket.getInputStream(), controlSocket);
        } catch (IOException | RuntimeException e) {
            // A failed video channel ends the whole session: close the control channel too
            // (the phone does the same on its side), instead of leaving it dangling
            close();
            throw e;
        }
    }

    /** Control channel output (valid after {@link #connect}). */
    public OutputStream getControlOutputStream() {
        return controlOut;
    }

    /**
     * Control channel input (valid after {@link #connect}): carries the device messages
     * (phone -&gt; car). Handed to {@code DeviceMessageReader} once the session is up; must
     * not be read anywhere else (the handshake already consumed its bytes from this stream).
     */
    public InputStream getControlInputStream() {
        return controlIn;
    }

    /**
     * Video stream input (valid after {@link #connectVideo}). Reads time out periodically and
     * probe the control channel before retrying; this is transparent to the caller, which only
     * ever sees a blocking read or a real I/O failure.
     */
    public InputStream getVideoInputStream() {
        return videoIn;
    }

    /**
     * Close both channels; idempotent, safe from any thread. Closing a socket unblocks any
     * thread currently reading or writing it, which is how a session teardown propagates.
     * Closing either channel also terminates the whole session on the phone side.
     */
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeQuietly(videoSocket);
        closeQuietly(controlSocket);
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    /**
     * Video stream wrapper turning read timeouts into control-channel liveness probes.
     * <p>
     * A video read that times out means "no frame for a while": normal on a static screen
     * (the phone's encoder blocks until the virtual display changes), but also the only
     * symptom of a half-open phone (e.g. its service crashed). Distinguish the two by sending
     * a TCP urgent byte on the control channel: the phone never enables OOBINLINE, so the
     * byte is discarded by its TCP stack and never enters the application protocol, while a
     * dead peer (RST already received) makes the probe fail immediately, which surfaces here
     * as a read failure and tears the session down instead of freezing the picture forever.
     */
    private static final class StallProbeInputStream extends InputStream {

        private final InputStream in;
        private final Socket probeSocket;

        StallProbeInputStream(InputStream in, Socket probeSocket) {
            this.in = in;
            this.probeSocket = probeSocket;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            for (;;) {
                try {
                    return in.read(buffer, offset, length);
                } catch (SocketTimeoutException e) {
                    // Throws IOException if the phone is gone; otherwise keep waiting for frames
                    probeSocket.sendUrgentData(0);
                }
            }
        }

        @Override
        public int read() throws IOException {
            // Unused by PacketReader (it reads byte arrays); implemented for completeness
            byte[] single = new byte[1];
            int count = read(single, 0, 1);
            return count < 0 ? -1 : single[0] & 0xff;
        }
    }
}
