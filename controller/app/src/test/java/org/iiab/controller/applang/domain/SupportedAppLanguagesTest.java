package org.iiab.controller.applang.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** JVM unit test (runs in CI) for the app-language option list + lookup. */
public class SupportedAppLanguagesTest {

    @Test
    public void systemDefaultIsFirstWithEmptyTag() {
        List<AppLanguage> list = SupportedAppLanguages.all("System default");
        assertTrue(list.get(0).isSystemDefault());
        assertEquals("", list.get(0).tag());
        assertEquals("System default", list.get(0).toString());
    }

    @Test
    public void includesShippedLocales() {
        List<AppLanguage> list = SupportedAppLanguages.all("sys");
        // Keep in sync with the shipped values-* resource folders (ADFA-4537, Track A).
        String[] shipped = {"en", "de", "it", "ar", "ja", "zh-CN", "ko", "nl", "tr", "vi", "pl", "cs", "id", "fa", "uk", "ro", "el", "sk", "bg", "sr", "lt", "no", "hu", "az", "es", "fr", "hi", "pt", "ru-RU"};
        assertEquals(shipped.length + 1, list.size()); // +1 for system default at index 0
        for (String tag : shipped) {
            assertTrue("Missing shipped locale: " + tag, SupportedAppLanguages.indexOfTag(list, tag) > 0);
        }
        assertEquals(1, SupportedAppLanguages.indexOfTag(list, "en"));
        assertEquals("Русский", list.get(SupportedAppLanguages.indexOfTag(list, "ru-RU")).toString());
    }

    @Test
    public void unknownOrNullTagFallsBackToSystemDefault() {
        List<AppLanguage> list = SupportedAppLanguages.all("sys");
        assertEquals(0, SupportedAppLanguages.indexOfTag(list, "zz"));
        assertEquals(0, SupportedAppLanguages.indexOfTag(list, null));
        assertEquals(0, SupportedAppLanguages.indexOfTag(list, ""));
    }
}
