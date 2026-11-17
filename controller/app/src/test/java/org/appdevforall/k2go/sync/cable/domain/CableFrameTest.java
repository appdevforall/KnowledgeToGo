package org.appdevforall.k2go.sync.cable.domain;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

public class CableFrameTest {

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

    @Test
    public void roundTrip() {
        CableFrame f = new CableFrame(CableFrame.Type.DATA, 513, new byte[]{1, 2, 3});
        List<CableFrame> got = new FrameDecoder().feed(f.encode(), 0, CableFrame.HEADER + 3);
        assertEquals(1, got.size());
        assertEquals(CableFrame.Type.DATA, got.get(0).type);
        assertEquals(513, got.get(0).channel);
        assertArrayEquals(new byte[]{1, 2, 3}, got.get(0).payload);
    }

    @Test
    public void framesSplitAcrossReads() {
        byte[] all = concat(CableFrame.control(CableFrame.Type.OPEN, 7).encode(),
                new CableFrame(CableFrame.Type.DATA, 7, new byte[1000]).encode(),
                CableFrame.control(CableFrame.Type.CLOSE, 7).encode());
        FrameDecoder d = new FrameDecoder();
        List<CableFrame> got = new ArrayList<>();
        for (int i = 0; i < all.length; i += 3) got.addAll(d.feed(all, i, Math.min(3, all.length - i)));
        assertEquals(3, got.size());
        assertEquals(CableFrame.Type.OPEN, got.get(0).type);
        assertEquals(1000, got.get(1).payload.length);
        assertEquals(CableFrame.Type.CLOSE, got.get(2).type);
        assertEquals(0, d.dropped());
    }

    @Test
    public void dropsStaleBytesBeforeAFrame() {
        byte[] stale = {0, 0, 0x55, 'K', 9, 9, 9};
        byte[] all = concat(stale, CableFrame.control(CableFrame.Type.RESET, 0).encode());
        FrameDecoder d = new FrameDecoder();
        List<CableFrame> got = d.feed(all, 0, all.length);
        assertEquals(1, got.size());
        assertEquals(CableFrame.Type.RESET, got.get(0).type);
        assertEquals(stale.length, d.dropped());
    }

    @Test
    public void rejectsOversizeLength() {
        byte[] bad = CableFrame.control(CableFrame.Type.DATA, 1).encode();
        bad[6] = 0x7F; // length far above MAX_PAYLOAD
        FrameDecoder d = new FrameDecoder();
        assertTrue(d.feed(bad, 0, bad.length).isEmpty());
        assertTrue(d.dropped() > 0);
    }
}
