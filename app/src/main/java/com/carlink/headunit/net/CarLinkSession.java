package com.carlink.headunit.net;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

/**
 * Owns the two TCP channels of a CarLink session and performs the JSON handshake.
 * <p>
 * Sequence (see carlink-protocol.md):
 * <ol>
 *   <li>{@link #connect}: TCP to the phone control port, send {@code hello}, receive {@code ready};</li>
 *   <li>{@link #connectVideo}: TCP to the video port from {@code ready};</li>
 *   <li>the control channel then carries scrcpy control messages (car -&gt; phone) and stays
 *       write-only for this client; the video channel carries the video stream (phone -&gt; car).</li>
 * </ol>
 * All methods are blocking and must be called from a worker thread, except {@link #close()}
 * which is safe to call from any thread (it unblocks pending I/O).
 */
public final class CarLinkSession {

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
        controlSocket.setTcpNoDelay(true);
        controlSocket.connect(new InetSocketAddress(host, port), timeoutMs);
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
            return new Ready(codec, videoPort);
        } catch (JSONException e) {
            close();
            // JSONException is checked in Android's org.json; building the hello message with
            // fixed keys cannot actually fail, but a malformed response must close the session
            throw new IOException("Handshake failed: malformed JSON", e);
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
        videoSocket.setTcpNoDelay(true);
        videoSocket.connect(new InetSocketAddress(host, videoPort), timeoutMs);
        videoIn = videoSocket.getInputStream();
    }

    /** Control channel output (valid after {@link #connect}). */
    public OutputStream getControlOutputStream() {
        return controlOut;
    }

    /** Video stream input (valid after {@link #connectVideo}). */
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
}
