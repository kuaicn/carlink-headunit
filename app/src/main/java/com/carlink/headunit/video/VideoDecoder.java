package com.carlink.headunit.video;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Thin wrapper around a hardware {@link MediaCodec} video decoder rendering onto a Surface.
 * <p>
 * The decoder is configured without any csd-* buffers: codec configuration (SPS/PPS/VPS)
 * arrives in-band as config packets (pts_and_flags bit62) and is fed with
 * {@link MediaCodec#BUFFER_FLAG_CODEC_CONFIG}, exactly as the CarLink protocol mandates.
 * <p>
 * {@link #feed} must be called from a single thread; {@link #stop()} and {@link #release()}
 * are safe to call from any thread.
 */
public final class VideoDecoder {

    public interface Listener {
        /**
         * Called on the decoder thread when the decoded video size becomes known
         * (MediaCodec.INFO_OUTPUT_FORMAT_CHANGED). The touch coordinate mapping depends on it.
         */
        void onVideoSizeChanged(int width, int height);
    }

    private static final long INPUT_TIMEOUT_US = 10_000; // 10 ms

    private final MediaCodec codec;
    private final Listener listener;
    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

    private volatile boolean running = true;
    private boolean released;
    private int videoWidth = -1;
    private int videoHeight = -1;

    /**
     * @param width/height the expected video size; in this protocol it always equals the
     *                     screen size reported in the handshake hello message
     */
    public VideoDecoder(String mimeType, int width, int height, Surface surface, Listener listener) throws IOException {
        this.listener = listener;
        MediaCodec c = null;
        try {
            c = MediaCodec.createDecoderByType(mimeType);
            MediaFormat format = MediaFormat.createVideoFormat(mimeType, width, height);
            // No csd-0/csd-1: config packets are fed as regular input with BUFFER_FLAG_CODEC_CONFIG
            c.configure(format, surface, null, 0);
            c.start();
        } catch (IOException | RuntimeException e) {
            // Do not leak the codec if configuration fails
            if (c != null) {
                c.release();
            }
            throw e;
        }
        codec = c;
    }

    /**
     * Feed one packet into the decoder and drain all immediately available output frames.
     * Frames are rendered as soon as they are decoded (low latency).
     */
    public void feed(byte[] data, int length, long pts, boolean isConfig) {
        while (running) {
            final int inputIndex;
            try {
                inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US);
            } catch (IllegalStateException e) {
                return; // codec released concurrently
            }
            if (inputIndex < 0) {
                // No free input slot: drain output and retry
                drainOutput();
                continue;
            }
            try {
                ByteBuffer buffer = codec.getInputBuffer(inputIndex);
                if (buffer == null || buffer.capacity() < length) {
                    // Never happens with sane bitrates; drop the packet but keep the slot recycling
                    codec.queueInputBuffer(inputIndex, 0, 0, 0, 0);
                    return;
                }
                buffer.clear();
                buffer.put(data, 0, length);
                int flags = isConfig ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG : 0;
                codec.queueInputBuffer(inputIndex, 0, length, pts, flags);
            } catch (IllegalStateException e) {
                return; // codec released concurrently
            }
            break;
        }
        drainOutput();
    }

    private void drainOutput() {
        for (;;) {
            final int outputIndex;
            try {
                outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0);
            } catch (IllegalStateException e) {
                return; // codec released concurrently
            }
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return;
            }
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                onFormatChanged(codec.getOutputFormat());
                continue;
            }
            if (outputIndex < 0) {
                continue;
            }
            // Render immediately; zero-size buffers carry no image
            codec.releaseOutputBuffer(outputIndex, bufferInfo.size > 0);
        }
    }

    private void onFormatChanged(MediaFormat format) {
        int width = format.getInteger(MediaFormat.KEY_WIDTH);
        int height = format.getInteger(MediaFormat.KEY_HEIGHT);
        // Prefer the visible crop rectangle when the codec reports one
        if (format.containsKey("crop-left") && format.containsKey("crop-right") && format.containsKey("crop-top")
                && format.containsKey("crop-bottom")) {
            width = format.getInteger("crop-right") - format.getInteger("crop-left") + 1;
            height = format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1;
        }
        if (width > 0 && height > 0 && (width != videoWidth || height != videoHeight)) {
            videoWidth = width;
            videoHeight = height;
            listener.onVideoSizeChanged(width, height);
        }
    }

    /** Ask {@link #feed} to return as soon as possible. Safe from any thread. */
    public void stop() {
        running = false;
    }

    /** Stop and release the codec. Call once, from the decoder thread. */
    public void release() {
        running = false;
        if (!released) {
            released = true;
            try {
                codec.stop();
            } catch (IllegalStateException ignored) {
                // codec already stopped or never started successfully
            }
            codec.release();
        }
    }
}
