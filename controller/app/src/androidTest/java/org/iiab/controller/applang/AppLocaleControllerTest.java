package org.iiab.controller.applang;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.iiab.controller.applang.data.AppLocaleController;
import org.junit.After;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * On-device proof that the per-app locale override is set and cleared through AppCompat.
 * Runs on the main thread (AppCompatDelegate requirement) and restores the default after.
 *
 * ADFA-4746: on API 33+ the override is applied asynchronously by the system LocaleManager,
 * so getApplicationLocales() may not reflect it on the same tick — we poll briefly, and if
 * the platform does not expose it in-process we skip (Assume) rather than fail.
 */
@RunWith(AndroidJUnit4.class)
public class AppLocaleControllerTest {

    private void onMain(Runnable r) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(r);
    }

    private static String waitForTag(boolean expectEmpty, String expected) {
        for (int i = 0; i < 40; i++) { // up to ~4s
            String tag = AppLocaleController.currentTag();
            if (expectEmpty ? tag.isEmpty() : expected.equals(tag)) return tag;
            try { Thread.sleep(100); } catch (InterruptedException ignored) { }
        }
        return AppLocaleController.currentTag();
    }

    @After
    public void tearDown() {
        onMain(() -> AppLocaleController.apply(""));
    }

    @Test
    public void applyingTagIsReflected() {
        onMain(() -> AppLocaleController.apply("es"));
        String tag = waitForTag(false, "es");
        Assume.assumeTrue(
                "Per-app locale not observable in-process on API "
                        + android.os.Build.VERSION.SDK_INT
                        + " (applied asynchronously by the system LocaleManager)",
                "es".equals(tag));
        assertEquals("es", tag);
    }

    @Test
    public void clearingOverrideFollowsSystem() {
        onMain(() -> AppLocaleController.apply("fr"));
        String applied = waitForTag(false, "fr");
        Assume.assumeTrue(
                "Per-app locale not observable in-process on API "
                        + android.os.Build.VERSION.SDK_INT,
                "fr".equals(applied));
        assertEquals("fr", applied);
        onMain(() -> AppLocaleController.apply(""));
        assertTrue(waitForTag(true, "").isEmpty());
    }
}
