package com.carlink.headunit.video;

/**
 * Fit-center (letterbox) placement of a video frame inside a container.
 * <p>
 * Used by the render path only: ProjectionActivity sizes the SurfaceView to
 * {@link #width} x {@link #height} (MediaCodec scales decoded frames to fill the whole
 * surface, so aspect-correct rendering is achieved by shaping the view; the parent layout
 * centers it at the letterbox offset). The touch path deliberately does NOT reuse this
 * placement: view coordinates are already relative to the fitted surface, and the
 * integer-rounded surface size is not exactly proportional to the video, so
 * TouchEventConverter maps them back with the exact per-axis inverse of the surface
 * stretch instead of a uniform scale/offset (which would misplace taps by ~1 video pixel).
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
