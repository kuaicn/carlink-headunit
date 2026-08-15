package com.carlink.headunit.video;

import com.carlink.headunit.net.Protocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads the CarLink video stream (see carlink-protocol.md "视频通道"):
 * <pre>
 *   +0  4B  codec id (big-endian): h264 = 0x68323634, h265 = 0x68323635
 *   +4  repeated packets:
 *         8B  pts_and_flags (BE s64): bit62 = config packet, bit61 = keyframe, low 61 bits = pts (us)
 *         4B  payload length N (BE u32, excluding this 12-byte header)
 *         NB  raw Annex-B data
 * </pre>
 * There is no device-name meta and no session meta packet: the codec id is the very first byte.
 */
public final class PacketReader {

    /** One video packet. Instances are reused across reads; copy the data if you keep it. */
    public static final class Packet {
        /** Presentation timestamp in microseconds; meaningless for config packets. */
        public long pts;
        /** bit62: codec config (SPS/PPS/VPS), to be fed with BUFFER_FLAG_CODEC_CONFIG. */
        public boolean isConfig;
        /** bit61: keyframe. */
        public boolean isKeyFrame;
        /** Internal buffer holding the Annex-B payload; valid up to {@link #length}. */
        public byte[] data;
        /** Payload length in {@link #data}. */
        public int length;
    }

    /** Sanity guard against a desynchronized stream. */
    private static final int MAX_PACKET_SIZE = 8 * 1024 * 1024;

    private final InputStream in;
    private final byte[] header = new byte[Protocol.PACKET_HEADER_SIZE];
    private final byte[] codecIdBytes = new byte[4];
    private final Packet packet = new Packet();

    public PacketReader(InputStream in) {
        this.in = in;
    }

    /** Read the 4-byte codec id at the very start of the stream. */
    public int readCodecId() throws IOException {
        Protocol.readFully(in, codecIdBytes, 0, 4);
        return Protocol.readIntBE(codecIdBytes, 0);
    }

    /**
     * Read one video packet (blocking).
     *
     * @return the shared {@link Packet} instance
     * @throws java.io.EOFException on a clean end of stream between packets (phone stopped
     *         the session)
     * @throws IOException          on I/O error, a truncated packet or a desynchronized/corrupt
     *         stream
     */
    public Packet readPacket() throws IOException {
        Protocol.readFully(in, header, 0, Protocol.PACKET_HEADER_SIZE);
        long ptsAndFlags = Protocol.readLongBE(header, 0);
        long length = Protocol.readIntBE(header, 8) & 0xffffffffL;
        if (length > MAX_PACKET_SIZE) {
            throw new IOException("Implausible packet length " + length + " (stream desynchronized?)");
        }
        packet.isConfig = (ptsAndFlags & Protocol.PACKET_FLAG_CONFIG) != 0;
        packet.isKeyFrame = (ptsAndFlags & Protocol.PACKET_FLAG_KEY_FRAME) != 0;
        packet.pts = ptsAndFlags & Protocol.PACKET_PTS_MASK;
        packet.length = (int) length;
        if (packet.data == null || packet.data.length < packet.length) {
            packet.data = new byte[packet.length];
        }
        try {
            Protocol.readFully(in, packet.data, 0, packet.length);
        } catch (EOFException e) {
            // The header was already consumed: a half-read payload means the connection
            // dropped mid-packet, not a clean end of session
            throw new IOException("Truncated packet: connection lost mid-payload (" + packet.length + " bytes)", e);
        }
        return packet;
    }
}
