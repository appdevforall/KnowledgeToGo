/*
 * ============================================================================
 * Name        : CableFrame.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : One frame of the Clone cable protocol (K2GO-576). The USB
 *               accessory link is a single byte stream; frames let several
 *               rsync connections share it and let a new session drop bytes
 *               that an old session left in the link. Pure JVM.
 *               ADR: controller/docs/ADR-K2GO-576-clone-over-aoa.md
 * ============================================================================
 */
package org.appdevforall.k2go.sync.cable.domain;

import java.nio.ByteBuffer;

public final class CableFrame {

    /** Header: magic 'K' 'C', version, type, channel (u16), payload length (s32). Big-endian. */
    public static final int HEADER = 10;
    public static final int MAX_PAYLOAD = 64 * 1024;
    static final byte MAGIC_0 = 'K';
    static final byte MAGIC_1 = 'C';
    static final byte VERSION = 1;

    public enum Type {
        /** Start of a session: the peer drops all open channels. */
        RESET,
        /** Pairing data that the QR code carries today (JSON payload). */
        HELLO,
        /** A new rsync connection on {@code channel}. */
        OPEN,
        DATA,
        /** End of {@code channel}, either direction. */
        CLOSE,
        /** The sender stopped sharing: the receiver ends its pull at once instead of retrying. */
        BYE,
        /** Receiver -> sender: one byte, 1 when the receiver reaches the sender on Wi-Fi. */
        WIFI,
        /** Receiver -> sender: the copy is complete; the cable can be removed (K2GO-584). */
        DONE,
        /** Keepalive, both directions: a link with no frame for a few seconds is dead (K2GO-584). */
        PING;

        static Type of(int code) {
            Type[] all = values();
            return code >= 0 && code < all.length ? all[code] : null;
        }
    }

    public final Type type;
    public final int channel;
    public final byte[] payload;

    public CableFrame(Type type, int channel, byte[] payload) {
        if (channel < 0 || channel > 0xFFFF) throw new IllegalArgumentException("channel " + channel);
        if (payload.length > MAX_PAYLOAD) throw new IllegalArgumentException("payload " + payload.length);
        this.type = type;
        this.channel = channel;
        this.payload = payload;
    }

    public static CableFrame control(Type type, int channel) {
        return new CableFrame(type, channel, new byte[0]);
    }

    /** Header plus payload, ready to write to the link. */
    public byte[] encode() {
        byte[] b = new byte[HEADER + payload.length];
        writeHeader(b, type, channel, payload.length);
        System.arraycopy(payload, 0, b, HEADER, payload.length);
        return b;
    }

    /** Writes a header into {@code buf[0..HEADER)}, for a payload already at {@code buf[HEADER..]}. */
    public static void writeHeader(byte[] buf, Type type, int channel, int length) {
        if (length < 0 || length > MAX_PAYLOAD) throw new IllegalArgumentException("payload " + length);
        ByteBuffer.wrap(buf, 0, HEADER)
                .put(MAGIC_0).put(MAGIC_1).put(VERSION).put((byte) type.ordinal())
                .putShort((short) channel).putInt(length);
    }
}
