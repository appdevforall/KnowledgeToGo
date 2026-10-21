package org.appdevforall.k2go.apptheme.data;

import static org.junit.Assert.assertEquals;

import androidx.appcompat.app.AppCompatDelegate;

import org.junit.Test;

public class AppThemeControllerTest {

    @Test
    public void pickableModesAreKept() {
        for (int mode : AppThemeController.MODES) {
            assertEquals(mode, AppThemeController.normalize(mode));
        }
    }

    @Test
    public void unknownValuesFollowTheSystem() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
                AppThemeController.normalize(AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY));
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppThemeController.normalize(42));
    }
}
