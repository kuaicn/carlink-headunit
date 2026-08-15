package com.carlink.headunit;

import android.app.Activity;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import com.carlink.headunit.net.CarLinkSession;
import com.carlink.headunit.net.Protocol;
import com.carlink.headunit.touch.TouchEventConverter;
import com.carlink.headunit.touch.TouchMessageSender;
import com.carlink.headunit.video.PacketReader;
import com.carlink.headunit.video.VideoDecoder;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
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

    /** Exit gesture: two taps within this window inside the top-left corner square. */
    private static final long EXIT_DOUBLE_TAP_MS = 400;
    private static final int EXIT_CORNER_SIZE_DP = 96;

    private TextView textStatus;

    private String phoneIp;
    private int controlPort;
    private int exitCornerSizePx;

    private final CarLinkSession session = new CarLinkSession();
    private final TouchEventConverter touchConverter = new TouchEventConverter();
    private final AtomicBoolean disconnecting = new AtomicBoolean(false);

    private Thread sessionThread;
    private volatile TouchMessageSender touchSender;
    private volatile VideoDecoder videoDecoder;

    private long lastCornerTapTime;
    private boolean firstTouchForwarded;

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
        SurfaceView surfaceView = findViewById(R.id.surface_view);
        surfaceView.getHolder().addCallback(this);
        surfaceView.setOnTouchListener(this::onSurfaceTouch);

        enterImmersiveMode();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterImmersiveMode();
        }
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
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // A surface can only be lost through user or system action (Home/power key, our own
        // teardown): the session cannot outlive it, but this is not an abnormal disconnect,
        // so tear down quietly instead of toasting a spurious failure
        disconnect(null, false);
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

            // 3. Control channel + JSON handshake
            CarLinkSession.Ready ready = session.connect(phoneIp, controlPort, metrics.widthPixels, metrics.heightPixels,
                    metrics.densityDpi, codecs, CONNECT_TIMEOUT_MS);
            Log.i(TAG, "handshake ok: codec=" + ready.codec + ", videoPort=" + ready.videoPort);

            // 4. Video channel
            showStatus(getString(R.string.projection_starting_video));
            session.connectVideo(phoneIp, ready.videoPort, CONNECT_TIMEOUT_MS);
            Log.i(TAG, "video channel connected");

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
                    (w, h) -> touchConverter.setVideoSize(w, h));
            showStatus(null); // streaming: hide the status overlay
            while (!disconnecting.get()) {
                PacketReader.Packet packet = reader.readPacket();
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
