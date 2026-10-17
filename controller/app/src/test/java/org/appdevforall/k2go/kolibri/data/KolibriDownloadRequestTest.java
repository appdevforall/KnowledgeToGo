package org.appdevforall.k2go.kolibri.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.appdevforall.k2go.kolibri.domain.ChannelSelection;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

/** K2GO-475: the Kolibri download body and the reading of the box's job result. */
public class KolibriDownloadRequestTest {

    private static final String CH = "95a52b386f2c485cb97dd60901674a98";
    private static final String N1 = "11111111111111111111111111111111";

    @Test
    public void wholeChannelOmitsNodeIds() throws Exception {
        JSONObject item = KolibriDownloadRequest.body(ChannelSelection.wholeChannel(CH))
                .getJSONArray("items").getJSONObject(0);
        assertEquals(CH, item.getString("channelId"));
        assertFalse(item.has("nodeIds"));
    }

    @Test
    public void subtreesAreSent() throws Exception {
        JSONObject item = KolibriDownloadRequest.body(
                ChannelSelection.ofSubtrees(CH, Arrays.asList(N1)))
                .getJSONArray("items").getJSONObject(0);
        assertEquals(N1, item.getJSONArray("nodeIds").getString(0));
    }

    @Test
    public void missingCountsOnlyAPartialOutcome() throws Exception {
        JSONObject partial = new JSONObject()
                .put("outcome", "partial").put("requested", 10).put("available", 7).put("notFound", 1);
        assertEquals(4, KolibriDownloadRequest.missing(partial));
        // The box owns the outcome rule: a complete result is 0 whatever the counts say.
        JSONObject complete = new JSONObject()
                .put("outcome", "complete").put("requested", 10).put("available", 7);
        assertEquals(0, KolibriDownloadRequest.missing(complete));
        assertEquals(0, KolibriDownloadRequest.missing(null));
    }

    @Test
    public void missingIgnoresNegativeOrAbsentCounts() throws Exception {
        JSONObject odd = new JSONObject().put("outcome", "partial").put("available", 3);
        assertEquals(0, KolibriDownloadRequest.missing(odd));
        assertTrue(KolibriDownloadRequest.missing(
                new JSONObject().put("outcome", "partial").put("notFound", 2)) == 2);
    }
}
