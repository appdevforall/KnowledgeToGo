package org.appdevforall.k2go.deploy.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public class TarHeaderGuardTest {

    private static byte[] header(String name, long size, char type, String prefix) {
        byte[] h = new byte[512];
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(n, 0, h, 0, Math.min(100, n.length));
        byte[] s = String.format("%011o", size).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(s, 0, h, 124, 11);
        h[156] = (byte) type;
        System.arraycopy("ustar".getBytes(StandardCharsets.US_ASCII), 0, h, 257, 5);
        if (prefix != null) {
            byte[] p = prefix.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(p, 0, h, 345, p.length);
        }
        return h;
    }

    private static void member(ByteArrayOutputStream out, String name, byte[] data, char type) {
        out.write(header(name, data.length, type, null), 0, 512);
        out.write(data, 0, data.length);
        int pad = (512 - data.length % 512) % 512;
        out.write(new byte[pad], 0, pad);
    }

    /** A header split across chunks may already be partly passed; tar must never get all of it. */
    private static void assertStoppedAt(int badAt, int fed) {
        assertTrue("fed " + fed + ", bad header at " + badAt, fed >= badAt && fed < badAt + 512);
    }

    private static int feed(TarHeaderGuard g, byte[] all, int chunk) {
        int fed = 0;
        for (int off = 0; off < all.length; off += chunk) {
            int len = Math.min(chunk, all.length - off);
            int ok = g.accept(all, off, len);
            fed += ok;
            if (ok < len) break;
        }
        return fed;
    }

    @Test
    public void safeArchivePassesWhole() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        member(out, "installed-rootfs/iiab/etc/hosts", "127.0.0.1 localhost\n".getBytes(), '0');
        // Data that looks like a header with "../" must not be read as a header.
        member(out, "installed-rootfs/iiab/fake", header("../evil", 0, '0', null), '0');
        member(out, "installed-rootfs/iiab/dir/", new byte[0], '5');
        out.write(new byte[1024], 0, 1024);
        byte[] all = out.toByteArray();
        for (int chunk : new int[]{1, 7, 512, 8192}) {
            TarHeaderGuard g = new TarHeaderGuard();
            assertEquals(all.length, feed(g, all, chunk));
            assertNull(g.rejected());
        }
    }

    @Test
    public void stopsBeforeAClimbingMember() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        member(out, "installed-rootfs/iiab/a", new byte[700], '0');
        int badAt = out.size();
        member(out, "installed-rootfs/../../etc/passwd", "x".getBytes(), '0');
        byte[] all = out.toByteArray();
        for (int chunk : new int[]{1, 100, 512, 8192}) {
            TarHeaderGuard g = new TarHeaderGuard();
            assertStoppedAt(badAt, feed(g, all, chunk));
            assertEquals("installed-rootfs/../../etc/passwd", g.rejected());
        }
    }

    @Test
    public void stopsBeforeAnAbsoluteMember() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        member(out, "/etc/passwd", new byte[3], '0');
        TarHeaderGuard g = new TarHeaderGuard();
        assertEquals(0, feed(g, out.toByteArray(), 8192));
        assertEquals("/etc/passwd", g.rejected());
    }

    @Test
    public void readsTheUstarPrefix() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(header("passwd", 0, '0', "../.."), 0, 512);
        TarHeaderGuard g = new TarHeaderGuard();
        assertEquals(0, feed(g, out.toByteArray(), 8192));
        assertEquals("../../passwd", g.rejected());
    }

    @Test
    public void readsAGnuLongName() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        member(out, "././@LongLink", "installed-rootfs/../../x\0".getBytes(), 'L');
        int badAt = out.size();
        member(out, "installed-rootfs/short", new byte[1], '0');
        TarHeaderGuard g = new TarHeaderGuard();
        assertStoppedAt(badAt, feed(g, out.toByteArray(), 300));
        assertEquals("installed-rootfs/../../x", g.rejected());
    }

    @Test
    public void readsAPaxPath() {
        String rec = "path=/abs/file\n";
        String pax = (rec.length() + 3) + " " + rec;   // "18 path=/abs/file\n"
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        member(out, "PaxHeader/file", pax.getBytes(), 'x');
        int badAt = out.size();
        member(out, "installed-rootfs/file", new byte[1], '0');
        TarHeaderGuard g = new TarHeaderGuard();
        assertEquals(badAt, feed(g, out.toByteArray(), 8192));
        assertEquals("/abs/file", g.rejected());
    }

    @Test
    public void paxPathParsesRecords() {
        String a = "30 mtime=1700000000.123456789\n";
        String b = "20 path=dir/a b.txt\n";
        assertEquals("dir/a b.txt", TarHeaderGuard.paxPath((a + b).getBytes()));
    }
}
