package org.iiab.controller.download.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/** ADFA-4676: the .meta4 parser must return the file-level SHA-256, not a piece hash. */
public class MetalinkFileTest {

    private static final String FILE_HASH =
            "77be6c5f3d0d6152ec8aafa6231756cda74a00d102dc03663a7b6fe7e92fd426";
    private static final String FIRST_PIECE_HASH =
            "729605f1ade347f1230ef0e08b9e6a128fbfb2a0fd06470881b6ffc1a8a4ca4b";

    private static final String META4 =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<metalink xmlns=\"urn:ietf:params:xml:ns:metalink\" version=\"4.0\">\n"
            + "  <file name=\"iiab-oa_2026.192_standard_17dd521_arm64-v8a.tar.gz\">\n"
            + "    <size>1453144579</size>\n"
            + "    <hash type=\"sha-256\">" + FILE_HASH + "</hash>\n"
            + "    <pieces type=\"sha-256\" length=\"1048576\">\n"
            + "      <hash type=\"sha-256\">" + FIRST_PIECE_HASH + "</hash>\n"
            + "      <hash type=\"sha-256\">67c19c9e0509ff1aaeb104fce7498f53f02052acb18a241409b6d58c9c114142</hash>\n"
            + "    </pieces>\n"
            + "    <url>https://iiab.switnet.org/android/rootfs/iiab-oa_2026.192_standard_17dd521_arm64-v8a.tar.gz</url>\n"
            + "    <url>https://mirror2.example.org/iiab-oa_2026.192_standard_17dd521_arm64-v8a.tar.gz</url>\n"
            + "  </file>\n"
            + "</metalink>\n";

    private MetalinkFile parse() throws Exception {
        try (InputStream in = new ByteArrayInputStream(META4.getBytes(StandardCharsets.UTF_8))) {
            return MetalinkFile.parse(in);
        }
    }

    @Test public void parsesFileName() throws Exception {
        assertEquals("iiab-oa_2026.192_standard_17dd521_arm64-v8a.tar.gz", parse().fileName());
    }

    @Test public void parsesSize() throws Exception {
        assertEquals(1453144579L, parse().sizeBytes());
    }

    @Test public void picksFileLevelHashNotAPieceHash() throws Exception {
        MetalinkFile mf = parse();
        assertEquals(FILE_HASH, mf.sha256());
        assertNotEquals(FIRST_PIECE_HASH, mf.sha256());
    }

    @Test public void countsHttpMirrors() throws Exception {
        assertEquals(2, parse().mirrors().size());
    }

    @Test public void canVerifyWhenComplete() throws Exception {
        assertTrue(parse().canVerify());
    }
}
