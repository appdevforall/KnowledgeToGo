/*
 * ============================================================================
 * Name        : RootfsIntegrityTest.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : End-to-end test of the dependency-free tar reader + integrity
 *               verifier against checked-in .tar.gz fixtures. The golden hashes
 *               were produced by the frozen reference tools/rootfs-builder/iiab_tree_hash.py.
 *               Fixtures cover pure ustar, GNU long-name ('L'), and pax ('x',
 *               incl. a non-ASCII name) — the formats Apache Commons Compress
 *               would have handled — proving our hand-rolled reader matches the
 *               build side byte-for-byte. Also covers MISMATCH, DECLARED_NONE
 *               (device backup) and ABSENT. None of these paths call android Log.
 * ============================================================================
 */
package org.iiab.controller.deploy.data;

import static org.junit.Assert.assertEquals;

import org.iiab.controller.deploy.data.RootfsIntegrity.Status;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class RootfsIntegrityTest {

    private static final String USTAR = "e892069fb8eb7d5eb199780cbb86a25bfe6a77fec013445139d092cf139a58ec";
    private static final String GNU   = "b5439690ac990e24dd591165441f254db51bbb3311779281289f9227d0878ad7";
    private static final String PAX   = "43dd453a891677e021ae6334f9b9d797e07e54d39fc653f21ce0e01f201df512";

    /** Copy a classpath fixture to a temp file (verify() takes a path). */
    private static String materialize(String fixture) throws Exception {
        File tmp = File.createTempFile("rootfs-fixture", ".tar.gz");
        tmp.deleteOnExit();
        try (InputStream in = RootfsIntegrityTest.class
                     .getResourceAsStream("/rootfs-fixtures/" + fixture);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
        }
        return tmp.getAbsolutePath();
    }

    private static RootfsIntegrity.Result verify(String fixture) throws Exception {
        return RootfsIntegrity.verify(materialize(fixture));
    }

    @Test
    public void plainUstarMatchesGolden() throws Exception {
        RootfsIntegrity.Result r = verify("ustar.tar.gz");
        assertEquals(Status.MATCH, r.status);
        assertEquals(USTAR, r.computedHash);
    }

    @Test
    public void gnuLongNameMatchesGolden() throws Exception {
        RootfsIntegrity.Result r = verify("gnu.tar.gz");
        assertEquals(Status.MATCH, r.status);
        assertEquals(GNU, r.computedHash);
    }

    @Test
    public void paxHeadersMatchGolden() throws Exception {
        RootfsIntegrity.Result r = verify("pax.tar.gz");
        assertEquals(Status.MATCH, r.status);
        assertEquals(PAX, r.computedHash);
    }

    @Test
    public void tamperedTreehashIsMismatch() throws Exception {
        assertEquals(Status.MISMATCH, verify("mismatch.tar.gz").status);
    }

    @Test
    public void declaredNoneIsDeviceBackup() throws Exception {
        assertEquals(Status.DECLARED_NONE, verify("none.tar.gz").status);
    }

    @Test
    public void noIntegrityMemberIsAbsent() throws Exception {
        assertEquals(Status.ABSENT, verify("absent.tar.gz").status);
    }
}
