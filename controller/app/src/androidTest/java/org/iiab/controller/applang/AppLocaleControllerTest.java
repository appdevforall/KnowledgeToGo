package org.iiab.controller.applang;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.iiab.controller.applang.data.AppLocaleController;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * On-device proof that the per-app locale override is set and cleared through AppCompat.
 * Runs on the main thread (AppCompatDelegate requirement) and restores the default after.
 */
@RunWith(AndroidJUnit4.class)
public class AppLocaleControllerTest {

    private void onMain(Runnable r) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(r);
    }

    @After
    public void tearDown() {
        onMain(() -> AppLocaleController.apply(""));
    }

    @Test
    public void applyingTagIsReflected() {
        onMain(() -> AppLocaleController.apply("es"));
        assertEquals("es", AppLocaleController.currentTag());
    }

    @Test
    public void clearingOverrideFollowsSystem() {
        onMain(() -> AppLocaleController.apply("fr"));
        assertEquals("fr", AppLocaleController.currentTag());
        onMain(() -> AppLocaleController.apply(""));
        assertTrue(AppLocaleController.currentTag().isEmpty());
    }
}
