package org.appdevforall.k2go.util;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Minimal reader for the slice of IIAB's flat flag files the app cares about:
 * top-level {@code <module>_install} / {@code <module>_enabled} flags in
 * {@code local_vars.yml}, and the {@code <role>_installed} completion markers in
 * {@code iiab_state.yml} (K2GO-393). All three are flat {@code key: bool} lines,
 * so one parser reads both files -- there is no second place that knows how to
 * parse them.
 *
 * <p>Pure logic (no Android, no I/O) so it is JVM-unit-testable. It is
 * intentionally <strong>not</strong> a general YAML parser — it splits on the
 * first {@code :} and does not handle nesting, quoting, block scalars or inline
 * comments. That limitation is tracked as tech-debt <strong>D14</strong>; this
 * extraction is the first step (isolate + test) toward replacing it with a real
 * YAML library.
 */
public final class LocalVarsYamlParser {

    private LocalVarsYamlParser() {
    }

    /**
     * Parses {@code _install} / {@code _enabled} / {@code _installed} flags into a
     * {@link JSONObject} of key → boolean. A value counts as {@code true} when it is
     * {@code true}, {@code yes} or {@code 1} (case-insensitive). Lines that are blank,
     * comments ({@code #}) or unrelated keys are ignored. Never returns {@code null}.
     */
    public static JSONObject parseToJson(String yaml) {
        JSONObject json = new JSONObject();
        if (yaml == null) {
            return json;
        }
        for (String line : yaml.split("\n")) {
            if (!line.contains(":") || line.trim().startsWith("#")) {
                continue;
            }
            String[] parts = line.split(":", 2);
            String key = parts[0].trim();
            String val = parts[1].trim().toLowerCase(Locale.ROOT);
            // "_installed" is the iiab_state completion marker; "_install"/"_enabled" are the
            // local_vars intent flags. endsWith is exclusive: "maps_installed" is not "_install".
            if (key.endsWith("_install") || key.endsWith("_enabled") || key.endsWith("_installed")) {
                boolean isTrue = val.equals("true") || val.equals("yes") || val.equals("1");
                try {
                    json.put(key, isTrue);
                } catch (Exception ignored) {
                    // JSONObject.put only throws on a null key, which cannot happen here.
                }
            }
        }
        return json;
    }
}
