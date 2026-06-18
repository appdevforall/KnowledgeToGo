package org.iiab.controller.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** Pure-JVM tests for the local_vars.yml flag reader. */
public class LocalVarsYamlParserTest {

    @Test
    public void readsInstallAndEnabledTrueVariants() {
        JSONObject j = LocalVarsYamlParser.parseToJson(
                "kiwix_install: True\n" +
                "kiwix_enabled: yes\n" +
                "kolibri_install: 1\n");
        assertTrue(j.optBoolean("kiwix_install"));
        assertTrue(j.optBoolean("kiwix_enabled"));
        assertTrue(j.optBoolean("kolibri_install"));
    }

    @Test
    public void readsFalseValues() {
        JSONObject j = LocalVarsYamlParser.parseToJson("kiwix_install: False\nkiwix_enabled: no\n");
        assertFalse(j.optBoolean("kiwix_install", true));
        assertFalse(j.optBoolean("kiwix_enabled", true));
    }

    @Test
    public void ignoresCommentsBlanksAndUnrelatedKeys() {
        JSONObject j = LocalVarsYamlParser.parseToJson(
                "# kiwix_install: True\n" +
                "\n" +
                "some_other_key: True\n" +
                "version: 3.2\n");
        assertFalse(j.has("kiwix_install"));   // commented out
        assertFalse(j.has("some_other_key"));  // not an _install/_enabled flag
        assertFalse(j.has("version"));
    }

    @Test
    public void handlesValueContainingColon() {
        // split(":", 2) keeps everything after the first colon as the value.
        JSONObject j = LocalVarsYamlParser.parseToJson("note_enabled: true # at 10:30\n");
        // value is "true # at 10:30" -> not exactly "true", so it reads false.
        assertFalse(j.optBoolean("note_enabled", true));
    }

    @Test
    public void handlesNullAndEmpty() {
        assertFalse(LocalVarsYamlParser.parseToJson(null).keys().hasNext());
        assertFalse(LocalVarsYamlParser.parseToJson("").keys().hasNext());
    }
}
