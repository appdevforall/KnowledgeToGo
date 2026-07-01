package org.iiab.controller.feedback.domain;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Renders a {@link FeedbackPayload} into the JSON body the Cloudflare Worker accepts —
 * the same fields as the email, keyed with the Worker's snake_case names. Pure domain
 * (org.json only), fully JVM-testable. The {@code install_id} and the envelope id are
 * added by the transport / delivery backbone, not here.
 */
public final class FeedbackJson {

    public static final String PRODUCT = "K2Go";

    public JSONObject render(FeedbackPayload p) {
        JSONObject o = new JSONObject();
        try {
            o.put("product", PRODUCT);
            o.put("type", p.type().wire());
            o.put("message", p.message());
            o.put("app_build", p.appBuild());
            putOpt(o, "contact", p.contactEmail());
            putOpt(o, "app_version", p.appVersion());
            putOpt(o, "android", p.androidRelease());
            putOpt(o, "device", p.device());
            putOpt(o, "abi", p.abi());
            putOpt(o, "rootfs_build", p.rootfsBuild());
            if (p.rootfsChecksum() != null) {
                o.put("rootfs_checksum", p.rootfsChecksum().label());
            }
            putOpt(o, "binaries_tag", p.binariesTag());
            putOpt(o, "screen", p.screen());
            putOpt(o, "stack_trace", p.stackTrace());
        } catch (JSONException ignored) {
            // org.json only throws on NaN/Infinity or null keys; not possible here.
        }
        return o;
    }

    private static void putOpt(JSONObject o, String key, String value) throws JSONException {
        if (value != null && !value.isEmpty()) {
            o.put(key, value);
        }
    }
}
