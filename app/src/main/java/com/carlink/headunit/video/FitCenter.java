package com.carlink.headunit.video;

/**
 * Fit-center (letterbox) placement of a video frame inside a container.
 * <p>
 * This is the single implementation of the scale/offset math shared by the two consumers
 * of the decoded video size, which must never disagree: the render path
 * (ProjectionActivity sizes the SurfaceView to {@link #width} x {@link #height}, since
 * MediaCodec scales decoded frames to fill the whole surface) and the touch path
 * (TouchEventConverter maps view coordinates back into video pixels through
 * {@link #scale}/{@link #offsetX}/{@link #offsetY}).
 */
public final class FitCenter {

    /** Uniform scale factor applied to the video (container pixels per video pixel). */
    public final float scale;
    /** Left/top letterbox offset of the scaled video inside the container, in pixels. */
    public final float offsetX;
    public final float offsetY;
    /** Scaled video size in container pixels (at least 1px; may round 1px short of the container). */
    public final int width;
    public final int height;

    private FitCenter(float scale, float offsetX, float offsetY, int width, int height) {
        this.scale = scale;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.width = width;
        this.height = height;
    }

    /** Placement of a videoW x videoH video fit-centered inside containerW x containerH. */
    public static FitCenter compute(int containerW, int containerH, int videoW, int videoH) {
        float scale = Math.min((float) containerW / videoW, (float) containerH / videoH);
        return new FitCenter(scale,
                (containerW - videoW * scale) / 2f,
                (containerH - videoH * scale) / 2f,
                Math.max(1, Math.round(videoW * scale)),
                Math.max(1, Math.round(videoH * scale)));
    }
}
