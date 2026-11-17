package org.appdevforall.k2go.contentexchange.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.appdevforall.k2go.contentexchange.domain.ShareProgress;
import org.json.JSONObject;
import org.junit.Test;

/** K2GO-577: conex-core's share progress JSON to the domain view. */
public class ShareProgressReaderTest {

    @Test
    public void readsAnOpenSession() throws Exception {
        ShareProgress p = ShareProgressReader.parse(new JSONObject(
                "{\"sessionId\":\"s\",\"state\":\"open\",\"items\":[{\"itemRef\":\"0\",\"movedBytes\":3,"
                        + "\"totalBytes\":5,\"state\":\"running\",\"delivered\":false}]}"));
        assertEquals(ShareProgress.State.OPEN, p.state);
        assertTrue(p.hasActivity());
        assertEquals(3, p.movedBytes());
    }

    @Test
    public void aBoxBeforeTheStateFieldIsOpen() throws Exception {
        ShareProgress p = ShareProgressReader.parse(new JSONObject("{\"sessionId\":\"s\",\"items\":[]}"));
        assertEquals(ShareProgress.State.OPEN, p.state);
        assertFalse(p.hasActivity());
    }

    @Test
    public void readsHowItEnded() throws Exception {
        assertEquals(ShareProgress.State.COMPLETED,
                ShareProgressReader.parse(new JSONObject("{\"state\":\"completed\",\"items\":[]}")).state);
        assertEquals(ShareProgress.State.CANCELLED,
                ShareProgressReader.parse(new JSONObject("{\"state\":\"cancelled\"}")).state);
        assertEquals(ShareProgress.State.EXPIRED,
                ShareProgressReader.parse(new JSONObject("{\"state\":\"expired\"}")).state);
        assertEquals(ShareProgress.State.GONE, ShareProgressReader.gone().state);
    }

    @Test
    public void anHttpDeliveryHasNoBytes() throws Exception {
        ShareProgress p = ShareProgressReader.parse(new JSONObject(
                "{\"state\":\"open\",\"items\":[{\"itemRef\":\"1\",\"delivered\":true}]}"));
        assertEquals(1, p.deliveredCount());
        assertEquals(0, p.movedBytes());
    }
}
