package com.carlink.headunit.touch;

import android.view.MotionEvent;

import com.carlink.headunit.net.Protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts {@link MotionEvent}s from the projection surface into serialized CarLink touch
 * control messages. Called on the UI thread only; {@link #setVideoSize} may be called from
 * the decoder thread (packed into one volatile long for atomic cross-thread visibility).
 * <p>
 * Coordinate mapping: the video is rendered fit-center inside the view (letterboxed), so
 * view coordinates are mapped back to video pixels with the same scale/offset. The
 * screenWidth/screenHeight message fields carry the <b>current video size</b>: the server
 * (PositionMapper.map) silently drops any event whose size does not match the video.
 */
public final class TouchEventConverter {

    /** (width &lt;&lt; 32) | height; 0 until the first decoded frame reports the real size. */
    private volatile long videoSize;

    private int viewWidth;
    private int viewHeight;

    /* True while a gesture is in progress whose initial DOWN was dropped (the video size was
     * unknown at that moment): everything until the final UP/CANCEL must be dropped too, so
     * the server never receives MOVE/UP for a pointer it never saw go down (a bare UP would
     * make Controller.injectTouch build an invalid 0-pointer MotionEvent). UI thread only. */
    private boolean gestureSuppressed;

    /** Called from the decoder thread on MediaCodec.INFO_OUTPUT_FORMAT_CHANGED. */
    public void setVideoSize(int width, int height) {
        videoSize = ((long) width << 32) | (height & 0xffffffffL);
    }

    /** Called on surface changes with the current view size in pixels. */
    public void setViewSize(int width, int height) {
        viewWidth = width;
        viewHeight = height;
    }

    /**
     * Convert one MotionEvent to the control messages to send, in order.
     *
     * @return the serialized messages (possibly empty, e.g. while the video size is unknown)
     */
    public List<byte[]> convert(MotionEvent event) {
        List<byte[]> messages = new ArrayList<>();
        int action = event.getActionMasked();
        if (gestureSuppressed) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                // The suppressed gesture is over; the next DOWN starts a fresh one
                gestureSuppressed = false;
            }
            return messages;
        }

        long packed = videoSize;
        int videoW = (int) (packed >>> 32);
        int videoH = (int) packed;
        if (videoW <= 0 || videoH <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            /* Video size unknown yet: drop events rather than send coordinates the server
             * would discard anyway (it validates screenWidth/screenHeight against the video).
             * If this drops a DOWN, suppress the rest of the gesture as well: the matching
             * UP sent later (once the size is known) would reach a server that never saw the
             * pointer go down. */
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                gestureSuppressed = true;
            }
            return messages;
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                /* Send a plain DOWN for the finger that just touched. The client must NOT send
                 * ACTION_POINTER_DOWN: the server derives POINTER_DOWN/UP itself from the pointer
                 * count (Controller.injectTouch) and only releases pointers on plain ACTION_UP. */
                int index = event.getActionIndex();
                add(messages, Protocol.ACTION_DOWN, event.getPointerId(index), event.getX(index), event.getY(index), videoW, videoH,
                        Protocol.PRESSURE_PRESSED);
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                // Plain UP for the lifted finger; the server turns it into POINTER_UP
                int index = event.getActionIndex();
                add(messages, Protocol.ACTION_UP, event.getPointerId(index), event.getX(index), event.getY(index), videoW, videoH,
                        Protocol.PRESSURE_RELEASED);
                break;
            }
            case MotionEvent.ACTION_UP: {
                // Last finger lifted; release every pointer the event still reports (defensive:
                // normally exactly one remains at this point)
                for (int i = 0; i < event.getPointerCount(); ++i) {
                    add(messages, Protocol.ACTION_UP, event.getPointerId(i), event.getX(i), event.getY(i), videoW, videoH,
                            Protocol.PRESSURE_RELEASED);
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                int pointerCount = event.getPointerCount();
                // Replay the historical batches first (one MOVE per pointer per sample) for
                // smoother motion, then the current positions
                int historySize = event.getHistorySize();
                for (int h = 0; h < historySize; ++h) {
                    for (int i = 0; i < pointerCount; ++i) {
                        add(messages, Protocol.ACTION_MOVE, event.getPointerId(i), event.getHistoricalX(i, h),
                                event.getHistoricalY(i, h), videoW, videoH, Protocol.PRESSURE_PRESSED);
                    }
                }
                for (int i = 0; i < pointerCount; ++i) {
                    add(messages, Protocol.ACTION_MOVE, event.getPointerId(i), event.getX(i), event.getY(i), videoW, videoH,
                            Protocol.PRESSURE_PRESSED);
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                /* The wire protocol has no CANCEL semantics: the server only releases a pointer
                 * when it receives ACTION_UP (Controller.injectTouch: setUp(action == ACTION_UP)).
                 * Sending CANCEL would leak the pointer state server-side, so release every
                 * active pointer with UP instead (same as the upstream scrcpy client, which
                 * never sends CANCEL at all). */
                for (int i = 0; i < event.getPointerCount(); ++i) {
                    add(messages, Protocol.ACTION_UP, event.getPointerId(i), event.getX(i), event.getY(i), videoW, videoH,
                            Protocol.PRESSURE_RELEASED);
                }
                break;
            }
            default:
                break;
        }
        return messages;
    }

    private void add(List<byte[]> messages, int action, long pointerId, float viewX, float viewY, int videoW, int videoH,
            int pressure) {
        // Fit-center (letterbox) mapping: view coordinates -> video pixels
        float scale = Math.min((float) viewWidth / videoW, (float) viewHeight / videoH);
        float offsetX = (viewWidth - videoW * scale) / 2f;
        float offsetY = (viewHeight - videoH * scale) / 2f;
        /* Clamp rather than drop taps in the letterbox bars: a drag that leaves the video area
         * keeps moving along the nearest edge instead of freezing mid-gesture, the DOWN/MOVE/UP
         * stream stays complete (no extra suppression state), and wire coordinates never go
         * out of range. scale > 0 is guaranteed by the size check in convert(). */
        int x = clamp(Math.round((viewX - offsetX) / scale), videoW - 1);
        int y = clamp(Math.round((viewY - offsetY) / scale), videoH - 1);
        messages.add(Protocol.serializeTouchEvent(action, pointerId, x, y, videoW, videoH, pressure));
    }

    private static int clamp(int value, int max) {
        return value < 0 ? 0 : Math.min(value, max);
    }
}
