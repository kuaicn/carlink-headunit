package com.carlink.headunit;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import com.carlink.headunit.net.CarLinkSession;
import com.carlink.headunit.net.Protocol;
import com.carlink.headunit.touch.TouchEventConverter;
import com.carlink.headunit.touch.TouchMessageSender;
import com.carlink.headunit.video.FitCenter;
import com.carlink.headunit.video.PacketReader;
import com.carlink.headunit.video.VideoDecoder;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Full-screen projection screen.
 * <p>
 * A worker ("session") thread runs the whole pipeline, all of it blocking I/O:
 * connect control socket -&gt; handshake (real screen metrics + locally probed decoders) -&gt;
 * connect video socket -&gt; read packets -&gt; decode/render onto the SurfaceView. Any failure,
 * EOF or user exit tears the whole session down and returns to the connection screen.
 * <p>
 * Touch events are converted on the UI thread and serialized to the control socket by a
 * dedicated sender thread. The BACK key injects BACK into the phone's virtual display
 * instead of leaving (or cancels the connection attempt if no session exists yet);
 * double-tapping the top-left corner exits the projection.
 */
public class ProjectionActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "CarLinkHeadunit";

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final long SESSION_JOIN_TIMEOUT_MS = 1500;

    /** A failed connection attempt is retried this many times with RETRY_DELAY_MS between
     * attempts; only transient failures qualify (see {@link #isRetriable}). */
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 2000;

    /** Stall hint: after a forwarded touch, no new frame within this window means the
     * network is dropping the stream (a merely static picture produces no touch first). */
    private static final long STALL_HINT_MS = 2500;
    private static final long STALL_CHECK_INTERVAL_MS = 1000;

    /** Exit gesture: two taps within this window inside the top-left corner square. */
    private static final long EXIT_DOUBLE_TAP_MS = 400;
    private static final int EXIT_CORNER_SIZE_DP = 96;

    private TextView textStatus;
    private SurfaceView surfaceView;

    private String phoneIp;
    private int controlPort;
    private int exitCornerSizePx;

    // Volatile: disconnect() closes it from any thread, and a retried connection attempt
    // replaces it with a fresh instance (a failed connect closes the previous one)
    private volatile CarLinkSession session = new CarLinkSession();
    private final TouchEventConverter touchConverter = new TouchEventConverter();
    private final AtomicBoolean disconnecting = new AtomicBoolean(false);

    /** (width << 32) | height of the decoded video; 0 until the first FORMAT_CHANGED.
     * Written on the decoder thread, read on the UI thread (same packing as TouchEventConverter). */
    private volatile long videoSize;

    private Thread sessionThread;
    private volatile TouchMessageSender touchSender;
    private volatile VideoDecoder videoDecoder;

    private long lastCornerTapTime;
    private boolean firstTouchForwarded;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    /** Last forwarded touch (UI thread) and last received video packet (session thread),
     * both uptimeMillis; the stall checker compares the two. */
    private volatile long lastTouchTime;
    private volatile long lastFrameTime;
    /** True once the first frame is on screen; the stall hint is meaningless before that. */
    private volatile boolean streaming;
    private boolean stallHintShown; // UI thread only

    /**
     * Periodic stall-hint check (UI thread). A static phone screen streams nothing for an
     * unbounded time by design (the encoder blocks until the picture changes), so "no frames
     * for a while" alone is not a problem signal and must not raise a hint. But a touch
     * always changes the picture, so if no frame follows within {@link #STALL_HINT_MS} the
     * network is almost certainly dropping the stream — say so while the picture is frozen,
     * and hide the hint as soon as frames resume.
     */
    private final Runnable stallChecker = new Runnable() {
        @Override
        public void run() {
            boolean stalled = streaming && lastTouchTime > lastFrameTime
                    && SystemClock.uptimeMillis() - lastTouchTime > STALL_HINT_MS;
            if (stalled != stallHintShown) {
                stallHintShown = stalled;
                textStatus.setText(R.string.projection_stalled);
                textStatus.setVisibility(stalled ? View.VISIBLE : View.GONE);
            }
            uiHandler.postDelayed(this, STALL_CHECK_INTERVAL_MS);
        }
    };

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_projection);

        phoneIp = getIntent().getStringExtra(MainActivity.EXTRA_IP);
        controlPort = getIntent().getIntExtra(MainActivity.EXTRA_PORT, Protocol.DEFAULT_CONTROL_PORT);
        exitCornerSizePx = (int) (EXIT_CORNER_SIZE_DP * getResources().getDisplayMetrics().density + 0.5f);

        textStatus = findViewById(R.id.text_status);
        surfaceView = findViewById(R.id.surface_view);
        surfaceView.getHolder().addCallback(this);
        surfaceView.setOnTouchListener(this::onSurfaceTouch);

        enterImmersiveMode();
        uiHandler.postDelayed(stallChecker, STALL_CHECK_INTERVAL_MS);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterImmersiveMode();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // The session survives rotations (configChanges in the manifest): re-fit the video
        // surface once the parent has been re-laid out with the new screen size
        surfaceView.post(this::layoutVideoSurface);
    }

    private void enterImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacks(stallChecker);
        disconnect(null, false);
        Thread t = sessionThread;
        if (t != null) {
            try {
                t.join(SESSION_JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // Surface lifecycle drives the session
    // ------------------------------------------------------------------

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (disconnecting.get()) {
            return;
        }
        sessionThread = new Thread(() -> runSession(holder.getSurface()), "carlink-session");
        sessionThread.start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        touchConverter.setViewSize(width, height);
        layoutVideoSurface();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // A surface can only be lost through user or system action (Home/power key, our own
        // teardown): the session cannot outlive it, but this is not an abnormal disconnect,
        // so tear down quietly instead of toasting a spurious failure
        disconnect(null, false);
    }

    /**
     * Size the SurfaceView to the video's fit-center rect inside its parent (UI thread only).
     * MediaCodec stretches decoded frames to fill the whole surface, so aspect-correct
     * rendering is achieved by shaping the view itself; the touch mapping recomputes the
     * same FitCenter placement from the resulting view size, so picture and input stay
     * aligned even if the video aspect ever differs from the screen's. Resizing a
     * SurfaceView only re-sizes its surface (surfaceChanged), it is never destroyed, so
     * the running decoder keeps its surface. No-op until the video size is known, and
     * guarded against re-setting identical layout params (which would re-trigger
     * surfaceChanged in a loop).
     */
    private void layoutVideoSurface() {
        long packed = videoSize;
        int videoW = (int) (packed >>> 32);
        int videoH = (int) packed;
        View parent = (View) surfaceView.getParent();
        if (videoW <= 0 || videoH <= 0 || parent == null || parent.getWidth() <= 0 || parent.getHeight() <= 0) {
            return;
        }
        FitCenter fit = FitCenter.compute(parent.getWidth(), parent.getHeight(), videoW, videoH);
        ViewGroup.LayoutParams lp = surfaceView.getLayoutParams();
        if (lp.width != fit.width || lp.height != fit.height) {
            lp.width = fit.width;
            lp.height = fit.height;
            surfaceView.setLayoutParams(lp);
        }
    }

    // ------------------------------------------------------------------
    // Session pipeline (worker thread)
    // ------------------------------------------------------------------

    private void runSession(android.view.Surface surface) {
        String reason = null;
        try {
            // 1. Real physical screen parameters; the phone creates its VirtualDisplay
            //    (and the encoder) with exactly these values
            DisplayMetrics metrics = new DisplayMetrics();
            Display display = getWindowManager().getDefaultDisplay();
            display.getRealMetrics(metrics);

            // 2. Probe local hardware decoders for the hello "codecs" list
            List<String> codecs = probeVideoCodecs();
            if (codecs.isEmpty()) {
                throw new IOException(getString(R.string.error_no_decoder));
            }
            Log.i(TAG, "session starting: " + phoneIp + ":" + controlPort + ", screen "
                    + metrics.widthPixels + "x" + metrics.heightPixels + "/" + metrics.densityDpi + ", codecs=" + codecs);

            // 3+4. Control channel + JSON handshake, then the video channel; transient
            //      failures (busy phone, network not up yet) are retried a few times
            CarLinkSession.Ready ready = connectWithRetry(metrics, codecs);
            Log.i(TAG, "handshake ok: codec=" + ready.codec + ", videoPort=" + ready.videoPort);

            // 5. Control message sender thread
            touchSender = new TouchMessageSender(session.getControlOutputStream(),
                    e -> disconnect(getString(R.string.reason_control_failed), true));
            touchSender.start();

            // 6. Video stream header, then the decode loop
            PacketReader reader = new PacketReader(session.getVideoInputStream());
            int codecId = reader.readCodecId();
            String mimeType = mimeFromCodecId(codecId);
            if (mimeType == null) {
                throw new IOException("Unknown video codec id: " + String.format("0x%08x", codecId));
            }
            Log.i(TAG, "video stream codec: " + mimeType);
            videoDecoder = new VideoDecoder(mimeType, metrics.widthPixels, metrics.heightPixels, surface,
                    new VideoDecoder.Listener() {
                        @Override
                        public void onVideoSizeChanged(int w, int h) {
                            // Decoder thread: the touch mapping needs the size immediately,
                            // the surface re-fit must happen on the UI thread
                            touchConverter.setVideoSize(w, h);
                            videoSize = ((long) w << 32) | (h & 0xffffffffL);
                            runOnUiThread(ProjectionActivity.this::layoutVideoSurface);
                        }

                        @Override
                        public void onFirstFrameRendered() {
                            streaming = true; // gate for the stall hint
                            showStatus(null); // streaming: hide the status overlay
                        }
                    });
            // The overlay stays up until the first decoded frame is actually on screen:
            // between a successful connection and that frame the surface is pitch black,
            // which reads as "dead" without a hint
            showStatus(getString(R.string.projection_waiting_video));
            while (!disconnecting.get()) {
                PacketReader.Packet packet = reader.readPacket();
                lastFrameTime = SystemClock.uptimeMillis();
                videoDecoder.feed(packet.data, packet.length, packet.pts, packet.isConfig);
            }
        } catch (EOFException e) {
            // The phone ended the session (user action or phone-side stop): a normal peer
            // disconnect, not an error
            reason = getString(R.string.reason_phone_closed);
            Log.i(TAG, "phone closed the session");
        } catch (IOException | RuntimeException e) {
            if (!disconnecting.get()) {
                reason = localizeFailure(e);
                Log.w(TAG, "session failed: " + reason, e);
            }
        } finally {
            // Runs on the session thread: stop the sender, release the codec, close both sockets
            TouchMessageSender sender = touchSender;
            if (sender != null) {
                sender.stop();
            }
            VideoDecoder decoder = videoDecoder;
            if (decoder != null) {
                decoder.release();
            }
            session.close();
        }
        if (reason != null) {
            // Abnormal termination: back to the connection screen with an explanation
            disconnect(reason, true);
        }
    }

    /**
     * Connect the control channel, perform the handshake and connect the video channel,
     * retrying transient failures a few times before giving up.
     * <p>
     * Worth retrying: the phone rejecting with "busy" (the session occupying it may end any
     * moment) and network-level failures (the phone's service may still be starting, the
     * hotspot route not up yet). Protocol-level rejections (no common codec, invalid display,
     * malformed responses) are deterministic and fail immediately. The wait between attempts
     * is interruptible, so BACK still cancels a retrying connection right away.
     */
    private CarLinkSession.Ready connectWithRetry(DisplayMetrics metrics, List<String> codecs) throws IOException {
        for (int attempt = 1;; attempt++) {
            if (disconnecting.get()) {
                // BACK while waiting between attempts: get out without a failure reason
                throw new IOException("cancelled");
            }
            // A retried attempt needs a fresh session: a failed connect has closed the old one
            if (attempt > 1) {
                session = new CarLinkSession();
            }
            try {
                CarLinkSession.Ready ready = session.connect(phoneIp, controlPort, metrics.widthPixels,
                        metrics.heightPixels, metrics.densityDpi, codecs, CONNECT_TIMEOUT_MS);
                showStatus(getString(R.string.projection_starting_video));
                session.connectVideo(phoneIp, ready.videoPort, CONNECT_TIMEOUT_MS);
                Log.i(TAG, "video channel connected");
                return ready;
            } catch (IOException | RuntimeException e) {
                if (attempt > MAX_RETRIES || !isRetriable(e)) {
                    throw e;
                }
                Log.i(TAG, "connect attempt " + attempt + " failed, retrying in " + RETRY_DELAY_MS + " ms: " + e);
                showStatus(getString(R.string.projection_retrying, attempt, MAX_RETRIES));
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException interrupted) {
                    // disconnect() interrupts this thread (e.g. BACK cancels the attempt)
                    Thread.currentThread().interrupt();
                    throw new IOException("cancelled", interrupted);
                }
            }
        }
    }

    /**
     * True for connect-stage failures that usually fix themselves shortly: the phone busy
     * with another session, or a network-level error (refused, timed out, unreachable — the
     * phone's service may still be starting). Protocol-level rejections are final.
     */
    private static boolean isRetriable(Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SocketException || t instanceof SocketTimeoutException) {
                return true;
            }
        }
        String message = e.getMessage();
        return message != null && message.toLowerCase(Locale.US).contains("busy");
    }

    /** Probe the local decoders to build the hello "codecs" list, in preference order. */
    private static List<String> probeVideoCodecs() {
        boolean h264 = false;
        boolean h265 = false;
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (info.isEncoder()) {
                continue;
            }
            for (String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_AVC)) {
                    h264 = true;
                } else if (type.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
                    h265 = true;
                }
            }
        }
        List<String> codecs = new ArrayList<>();
        if (h264) {
            codecs.add("h264");
        }
        if (h265) {
            codecs.add("h265");
        }
        return codecs;
    }

    private static String mimeFromCodecId(int codecId) {
        switch (codecId) {
            case Protocol.CODEC_ID_H264:
                return MediaFormat.MIMETYPE_VIDEO_AVC;
            case Protocol.CODEC_ID_H265:
                return MediaFormat.MIMETYPE_VIDEO_HEVC;
            default:
                return null;
        }
    }

    /**
     * Translate a low-level failure (English exception text, phone rejection token) into a
     * short Chinese reason. Unknown failures keep the raw text as a parenthesized detail
     * so they remain diagnosable.
     */
    private String localizeFailure(Exception e) {
        String detail = e.getMessage();
        if (detail == null || detail.isEmpty()) {
            detail = e.toString();
        }
        String text = (e.getClass().getSimpleName() + ": " + detail).toLowerCase(Locale.US);
        final int resId;
        if (e instanceof SocketTimeoutException || text.contains("timed out")) {
            resId = R.string.reason_timeout;
        } else if (text.contains("no_common_codec")) {
            resId = R.string.reason_no_common_codec;
        } else if (text.contains("invalid_display")) {
            resId = R.string.reason_invalid_display;
        } else if (text.contains("busy")) {
            resId = R.string.reason_busy;
        } else if (text.contains("handshake")) {
            resId = R.string.reason_handshake_failed;
        } else if (e instanceof ConnectException || text.contains("refused")) {
            resId = R.string.reason_refused;
        } else if (text.contains("unreachable") || text.contains("no route")) {
            resId = R.string.reason_unreachable;
        } else if (text.contains("reset") || text.contains("broken pipe") || text.contains("connection lost")) {
            resId = R.string.reason_phone_closed;
        } else if (e instanceof MediaCodec.CodecException || text.contains("codec") || text.contains("packet")) {
            resId = R.string.reason_decode_error;
        } else {
            return getString(R.string.reason_unmapped, detail);
        }
        return getString(resId);
    }

    // ------------------------------------------------------------------
    // Input: touch forwarding, back key, exit gesture
    // ------------------------------------------------------------------

    private boolean onSurfaceTouch(View view, MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && event.getX() < exitCornerSizePx
                && event.getY() < exitCornerSizePx) {
            long now = event.getEventTime();
            if (now - lastCornerTapTime < EXIT_DOUBLE_TAP_MS) {
                lastCornerTapTime = 0;
                disconnect(null, false); // user-requested exit
                return true;
            }
            lastCornerTapTime = now;
        }
        // Stall-check input: a touch always changes the picture, so it starts the clock
        // after which a missing frame means the network is stalling (event time is
        // uptimeMillis-based, the same clock the stall checker uses)
        lastTouchTime = event.getEventTime();
        TouchMessageSender sender = touchSender;
        if (sender != null) {
            sender.sendAll(touchConverter.convert(event));
            // One-time milestone (UI thread only): proves the touch forwarding path is alive
            if (!firstTouchForwarded) {
                firstTouchForwarded = true;
                Log.i(TAG, "first touch event forwarded");
            }
        }
        return true;
    }

    @Override
    public void onBackPressed() {
        // Inject BACK into the phone's virtual display (or turn its screen on) instead of
        // leaving the projection; one DOWN + one UP, as Controller.pressBackOrTurnScreenOn
        // injects the received action as-is for KEYCODE_BACK.
        TouchMessageSender sender = touchSender;
        if (sender != null) {
            sender.send(Protocol.serializeBackOrScreenOn(KeyEvent.ACTION_DOWN));
            sender.send(Protocol.serializeBackOrScreenOn(KeyEvent.ACTION_UP));
        } else {
            // No sender yet (still connecting): BACK cancels the connection attempt instead
            // of being swallowed, so the user is never trapped on the status screen
            disconnect(null, false);
        }
    }

    // ------------------------------------------------------------------
    // Teardown
    // ------------------------------------------------------------------

    /**
     * Tear the session down and leave this screen. Idempotent, safe from any thread.
     *
     * @param reason    disconnect cause shown to the user; null for a deliberate exit
     * @param showToast whether to toast the reason before finishing
     */
    private void disconnect(String reason, boolean showToast) {
        if (!disconnecting.compareAndSet(false, true)) {
            return;
        }
        // Unblock the session thread: closing the sockets fails pending reads/writes,
        // stopping the decoder fails pending dequeueInputBuffer calls
        session.close();
        VideoDecoder decoder = videoDecoder;
        if (decoder != null) {
            decoder.stop();
        }
        Thread t = sessionThread;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
        }
        runOnUiThread(() -> {
            if (showToast && reason != null && !reason.isEmpty()) {
                Toast.makeText(this, getString(R.string.toast_disconnected, reason), Toast.LENGTH_LONG).show();
            }
            Intent result = new Intent();
            result.putExtra(MainActivity.RESULT_EXTRA_DISCONNECT_REASON, reason);
            setResult(RESULT_OK, result);
            if (!isFinishing()) {
                finish();
            }
        });
    }

    private void showStatus(String text) {
        runOnUiThread(() -> {
            if (text == null) {
                textStatus.setVisibility(View.GONE);
            } else {
                textStatus.setText(text);
                textStatus.setVisibility(View.VISIBLE);
            }
        });
    }
}
