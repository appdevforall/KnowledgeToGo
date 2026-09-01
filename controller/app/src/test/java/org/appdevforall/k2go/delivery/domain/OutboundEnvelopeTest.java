package org.appdevforall.k2go.delivery.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class OutboundEnvelopeTest {

    @Test
    public void roundTripsThroughJson() throws Exception {
        OutboundEnvelope e = new OutboundEnvelope(
                "id-1", OutboundType.ANALYTICS, "{\"a\":1,\"b\":\"x\"}", 123L);
        String json = e.toJson();

        OutboundEnvelope back = OutboundEnvelope.fromJson(json);
        assertEquals("id-1", back.id());
        assertEquals(OutboundType.ANALYTICS, back.type());
        assertEquals(123L, back.createdAt());
        assertEquals(1, new JSONObject(back.payloadJson()).getInt("a"));

        JSONObject wire = new JSONObject(json);
        assertEquals("analytics", wire.getString("type"));
        assertTrue(wire.has("payload"));
    }

    @Test
    public void typeWireMapping() {
        assertEquals(OutboundType.FEEDBACK, OutboundType.fromWire("feedback"));
        assertEquals("analytics", OutboundType.ANALYTICS.wire());
    }
}
