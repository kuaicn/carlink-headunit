package com.carlink.headunit.net;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * CarLink wire protocol constants and serialization helpers.
 * <p>
 * Authorities (phone side, verified field by field):
 * <ul>
 *   <li>carlink-scrcpy/docs/carlink-protocol.md (handshake + video byte layout)</li>
 *   <li>carlink-scrcpy/src/com/genymobile/scrcpy/control/ControlMessageReader.java (control messages)</li>
 *   <li>carlink-scrcpy/src/com/genymobile/scrcpy/device/Streamer.java (video packet header)</li>
 * </ul>
 * All multi-byte fields are big-endian.
 */
public final class Protocol {

    /** Default control channel port on the phone. */
    public static final int DEFAULT_CONTROL_PORT = 27183;

    // ------------------------------------------------------------------
    // Video stream
    // ------------------------------------------------------------------

    /** First 4 bytes of the video stream: big-endian ASCII "h264". */
    public static final int CODEC_ID_H264 = 0x68323634;
    /** First 4 bytes of the video stream: big-endian ASCII "h265". */
    public static final int CODEC_ID_H265 = 0x68323635;

    /** Packet header: 8-byte pts_and_flags + 4-byte payload length. */
    public static final int PACKET_HEADER_SIZE = 12;
    /** pts_and_flags bit 62: codec config packet (SPS/PPS), pts meaningless. */
    public static final long PACKET_FLAG_CONFIG = 1L << 62;
    /** pts_and_flags bit 61: keyframe. */
    public static final long PACKET_FLAG_KEY_FRAME = 1L << 61;
    /** pts mask: the low 61 bits (bits 61-63 are flags; bit 63 is never set by this protocol). */
    public static final long PACKET_PTS_MASK = (1L << 61) - 1;

    // ------------------------------------------------------------------
    // Control messages (car -> phone)
    // ------------------------------------------------------------------

    public static final int TYPE_INJECT_TOUCH_EVENT = 2;
    public static final int TYPE_BACK_OR_SCREEN_ON = 4;

    /* MotionEvent action values. The client must only ever send plain DOWN/MOVE/UP:
     * the server derives ACTION_POINTER_DOWN/UP itself from the pointer count
     * (Controller.injectTouch), and it only releases a pointer when action == ACTION_UP. */
    public static final int ACTION_DOWN = 0; // MotionEvent.ACTION_DOWN
    public static final int ACTION_UP = 1;   // MotionEvent.ACTION_UP
    public static final int ACTION_MOVE = 2; // MotionEvent.ACTION_MOVE

    /** Serialized size of an INJECT_TOUCH_EVENT message. */
    public static final int TOUCH_MESSAGE_SIZE = 32;

    /** u16 fixed-point pressure: 0xffff decodes to 1.0f (Binary.u16FixedPointToFloat). */
    public static final int PRESSURE_PRESSED = 0xffff;
    /** Pressure for UP events. */
    public static final int PRESSURE_RELEASED = 0;

    /** Sanity limit for a handshake frame (4-byte length + UTF-8 JSON). */
    private static final int MAX_HANDSHAKE_FRAME_SIZE = 4096;

    private Protocol() {
    }

    // ------------------------------------------------------------------
    // Handshake framing
    // ------------------------------------------------------------------

    /** Write a handshake frame: 4-byte big-endian length + UTF-8 JSON payload. */
    public static void writeHandshakeFrame(OutputStream out, String json) throws IOException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        writeIntBE(out, payload.length);
        out.write(payload);
        out.flush();
    }

    /**
     * Read a handshake frame.
     *
     * @return the JSON payload, or {@code null} if the peer closed the connection without
     * sending anything (the phone refuses a handshake by simply closing the socket)
     */
    public static String readHandshakeFrame(InputStream in) throws IOException {
        byte[] header = new byte[4];
        int first = in.read();
        if (first < 0) {
            return null;
        }
        header[0] = (byte) first;
        readFully(in, header, 1, 3);
        int length = readIntBE(header, 0);
        if (length <= 0 || length > MAX_HANDSHAKE_FRAME_SIZE) {
            throw new IOException("Invalid handshake frame length: " + length);
        }
        byte[] payload = new byte[length];
        readFully(in, payload, 0, length);
        return new String(payload, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // Control message serialization
    // ------------------------------------------------------------------

    /**
     * Serialize an INJECT_TOUCH_EVENT message (32 bytes), the exact mirror of
     * ControlMessageReader.parseInjectTouchEvent():
     * <pre>
     *   +0  u8   type = 2
     *   +1  u8   action (MotionEvent ACTION_DOWN/UP/MOVE)
     *   +2  s64  pointerId
     *   +10 s32  x
     *   +14 s32  y
     *   +18 u16  screenWidth  (must equal the current video width, else the server drops the event)
     *   +20 u16  screenHeight (must equal the current video height)
     *   +22 u16  pressure, u16 fixed point (0xffff = 1.0; 0 on UP)
     *   +24 s32  actionButton = 0
     *   +28 s32  buttons = 0
     * </pre>
     */
    public static byte[] serializeTouchEvent(int action, long pointerId, int x, int y, int screenWidth, int screenHeight,
            int pressure) {
        byte[] msg = new byte[TOUCH_MESSAGE_SIZE];
        msg[0] = TYPE_INJECT_TOUCH_EVENT;
        msg[1] = (byte) action;
        writeLongBE(msg, 2, pointerId);
        writeIntBE(msg, 10, x);
        writeIntBE(msg, 14, y);
        writeShortBE(msg, 18, screenWidth);
        writeShortBE(msg, 20, screenHeight);
        writeShortBE(msg, 22, pressure);
        writeIntBE(msg, 24, 0); // actionButton: no button for plain touch
        writeIntBE(msg, 28, 0); // buttons: no buttons for plain touch
        return msg;
    }

    /**
     * Serialize a BACK_OR_SCREEN_ON message (2 bytes): type(u8)=4 + action(u8),
     * where action is a KeyEvent action (ACTION_DOWN=0 / ACTION_UP=1), mirroring
     * ControlMessageReader.parseBackOrScreenOnEvent().
     */
    public static byte[] serializeBackOrScreenOn(int action) {
        return new byte[]{TYPE_BACK_OR_SCREEN_ON, (byte) action};
    }

    // ------------------------------------------------------------------
    // Big-endian helpers
    // ------------------------------------------------------------------

    /** Read exactly {@code length} bytes, throwing EOFException if the stream ends first. */
    public static void readFully(InputStream in, byte[] buffer, int offset, int length) throws IOException {
        int total = 0;
        while (total < length) {
            int read = in.read(buffer, offset + total, length - total);
            if (read < 0) {
                throw new EOFException("End of stream");
            }
            total += read;
        }
    }

    public static int readIntBE(byte[] buf, int offset) {
        return ((buf[offset] & 0xff) << 24) | ((buf[offset + 1] & 0xff) << 16) | ((buf[offset + 2] & 0xff) << 8)
                | (buf[offset + 3] & 0xff);
    }

    public static long readLongBE(byte[] buf, int offset) {
        return ((long) (buf[offset] & 0xff) << 56) | ((long) (buf[offset + 1] & 0xff) << 48)
                | ((long) (buf[offset + 2] & 0xff) << 40) | ((long) (buf[offset + 3] & 0xff) << 32)
                | ((long) (buf[offset + 4] & 0xff) << 24) | ((long) (buf[offset + 5] & 0xff) << 16)
                | ((long) (buf[offset + 6] & 0xff) << 8) | (buf[offset + 7] & 0xffL);
    }

    public static void writeIntBE(byte[] buf, int offset, int value) {
        buf[offset] = (byte) (value >>> 24);
        buf[offset + 1] = (byte) (value >>> 16);
        buf[offset + 2] = (byte) (value >>> 8);
        buf[offset + 3] = (byte) value;
    }

    public static void writeShortBE(byte[] buf, int offset, int value) {
        buf[offset] = (byte) (value >>> 8);
        buf[offset + 1] = (byte) value;
    }

    public static void writeLongBE(byte[] buf, int offset, long value) {
        buf[offset] = (byte) (value >>> 56);
        buf[offset + 1] = (byte) (value >>> 48);
        buf[offset + 2] = (byte) (value >>> 40);
        buf[offset + 3] = (byte) (value >>> 32);
        buf[offset + 4] = (byte) (value >>> 24);
        buf[offset + 5] = (byte) (value >>> 16);
        buf[offset + 6] = (byte) (value >>> 8);
        buf[offset + 7] = (byte) value;
    }

    private static void writeIntBE(OutputStream out, int value) throws IOException {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }
}
