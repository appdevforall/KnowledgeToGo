package org.appdevforall.k2go.contentexchange.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** K2GO-577: the rsync block of a share manifest entry is read only when it is valid. */
public class ShareRsyncTargetTest {

    private static String entry(String rsync) {
        return "{\"app\":\"kiwix\",\"identity\":{\"localRef\":\"a.zim\"}"
                + (rsync != null ? ",\"rsync\":" + rsync : "") + "}";
    }

    @Test
    public void readsAValidBlock() {
        ShareRsyncTarget t = ShareRsyncTarget.fromEntry(entry("{\"port\":9873,\"module\":\"abc123-0\"}"));
        assertNotNull(t);
        assertEquals(9873, t.port());
        assertEquals("abc123-0", t.module());
    }

    @Test
    public void noBlockMeansHttpFetch() {
        assertNull(ShareRsyncTarget.fromEntry(entry(null)));
        assertNull(ShareRsyncTarget.fromEntry(null));
        assertNull(ShareRsyncTarget.fromEntry("not json"));
    }

    @Test
    public void rejectsABadPortOrModule() {
        assertNull(ShareRsyncTarget.fromEntry(entry("{\"port\":0,\"module\":\"abc-0\"}")));
        assertNull(ShareRsyncTarget.fromEntry(entry("{\"port\":70000,\"module\":\"abc-0\"}")));
        assertNull(ShareRsyncTarget.fromEntry(entry("{\"port\":9873,\"module\":\"../x\"}")));
        assertNull(ShareRsyncTarget.fromEntry(entry("{\"port\":9873,\"module\":\"a b\"}")));
        assertNull(ShareRsyncTarget.fromEntry(entry("{\"port\":9873,\"module\":\"\"}")));
    }
}
