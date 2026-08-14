package com.carlink.headunit.touch;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Serializes control messages onto the control socket from a dedicated thread, so the UI
 * thread never performs network I/O. Messages are queued in FIFO order; a write failure is
 * reported once through {@link ErrorListener} and stops the sender.
 */
public final class TouchMessageSender {

    public interface ErrorListener {
        /** Called on the sender thread when writing to the control socket fails. */
        void onSendError(IOException e);
    }

    private static final long JOIN_TIMEOUT_MS = 500;

    private final OutputStream out;
    private final ErrorListener errorListener;
    private final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();

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

    /** Queue one message; silently dropped once the sender is stopped. Safe from any thread. */
    public void send(byte[] message) {
        if (running) {
            queue.offer(message);
        }
    }

    /** Queue several messages preserving their order. Safe from any thread. */
    public void sendAll(List<byte[]> messages) {
        if (running) {
            queue.addAll(messages);
        }
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
                running = false;
                errorListener.onSendError(e);
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
    }
}
