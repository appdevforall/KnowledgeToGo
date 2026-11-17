/*
 * ============================================================================
 * Name        : FrameDecoder.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Rebuilds CableFrames from the reads of the USB link. A read can
 *               hold part of a frame or several frames. Bytes that do not
 *               start a valid header are dropped one at a time until a header
 *               matches, so the decoder recovers from bytes an old session left
 *               in the link. Not thread-safe: one reader thread owns it.
 * ============================================================================
 */
package org.appdevforall.k2go.sync.cable.domain;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class FrameDecoder {

    private byte[] buf = new byte[2 * (CableFrame.HEADER + CableFrame.MAX_PAYLOAD)];
    private int size;
    private long dropped;

    /** Bytes dropped so far because they did not start a valid frame. */
    public long dropped() {
        return dropped;
    }

    /** Adds {@code len} bytes and returns every frame they complete, in order. */
    public List<CableFrame> feed(byte[] data, int off, int len) {
        if (size + len > buf.length) buf = Arrays.copyOf(buf, Math.max(buf.length * 2, size + len));
        System.arraycopy(data, off, buf, size, len);
        size += len;

        List<CableFrame> out = new ArrayList<>();
        int pos = 0;
        while (size - pos >= CableFrame.HEADER) {
            ByteBuffer h = ByteBuffer.wrap(buf, pos, CableFrame.HEADER);
            byte m0 = h.get(), m1 = h.get(), version = h.get();
            CableFrame.Type type = CableFrame.Type.of(h.get());
            int channel = h.getShort() & 0xFFFF;
            int length = h.getInt();
            if (m0 != CableFrame.MAGIC_0 || m1 != CableFrame.MAGIC_1 || version != CableFrame.VERSION
                    || type == null || length < 0 || length > CableFrame.MAX_PAYLOAD) {
                pos++;
                dropped++;
                continue;
            }
            if (size - pos < CableFrame.HEADER + length) break;
            byte[] payload = Arrays.copyOfRange(buf, pos + CableFrame.HEADER, pos + CableFrame.HEADER + length);
            out.add(new CableFrame(type, channel, payload));
            pos += CableFrame.HEADER + length;
        }
        System.arraycopy(buf, pos, buf, 0, size - pos);
        size -= pos;
        return out;
    }

    /** Drops any partial frame, for a new session. */
    public void clear() {
        size = 0;
    }
}
