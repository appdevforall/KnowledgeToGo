package org.appdevforall.k2go.delivery.domain;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One durable item in the shared outbound queue. Transport-agnostic: the queue persists
 * it, WorkManager posts it to the Cloudflare Worker, which routes by {@link #type()}.
 *
 * <p>{@code payloadJson} is the text of a JSON object (the feedback or analytics body).
 */
public final class OutboundEnvelope {

    private final String id;
    private final OutboundType type;
    private final String payloadJson;
    private final long createdAt;

    public OutboundEnvelope(String id, OutboundType type, String payloadJson, long createdAt) {
        this.id = id;
        this.type = type;
        this.payloadJson = payloadJson;
        this.createdAt = createdAt;
    }

    public String id() { return id; }

    public OutboundType type() { return type; }

    public String payloadJson() { return payloadJson; }

    public long createdAt() { return createdAt; }

    public JSONObject toJsonObject() throws JSONException {
        return new JSONObject()
                .put("id", id)
                .put("type", type.wire())
                .put("created_at", createdAt)
                .put("payload", new JSONObject(payloadJson));
    }

    public String toJson() throws JSONException {
        return toJsonObject().toString();
    }

    public static OutboundEnvelope fromJson(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        return new OutboundEnvelope(
                o.getString("id"),
                OutboundType.fromWire(o.getString("type")),
                o.getJSONObject("payload").toString(),
                o.getLong("created_at"));
    }
}
