package org.iiab.controller;

import android.util.Log;

import com.termux.shared.termux.extrakeys.ExtraKeysConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.extrakeys.ExtraKeysView;

/**
 * IIAB default extra-keys layout for the embedded Termux terminal.
 *
 * <p>This logic previously lived as {@code loadIIABDefaultKeys()} added directly to the
 * vendored upstream class {@code ExtraKeysView} (in the {@code termux-source} submodule).
 * It only uses public Termux APIs ({@link ExtraKeysView#reload(ExtraKeysInfo, float)} and
 * the public {@link ExtraKeysInfo} constructor), so it now lives in the app instead. That
 * keeps the Termux fork a clean mirror of upstream and avoids merge conflicts on every
 * upstream sync (see {@code controller/docs/FORK_DELTA_ANALYSIS.md}, finding K1).
 */
public final class IIABExtraKeys {

    private static final String TAG = "IIAB-ExtraKeys";

    /**
     * Two-row extra-keys layout. These keys must stay in sync with the keys handled by
     * {@code MainActivity}'s {@code IExtraKeysView} click listener. Single source of truth.
     */
    public static final String DEFAULT_LAYOUT =
            "[\n" +
            "  ['ESC', '/', '-', 'HOME', 'UP', 'END', 'PGUP'],\n" +
            "  ['TAB', 'CTRL', 'ALT', 'LEFT', 'DOWN', 'RIGHT', 'PGDN']\n" +
            "]";

    private IIABExtraKeys() {
        // Utility class — no instances.
    }

    /**
     * Loads the IIAB default extra-keys layout into the given view via the public
     * {@link ExtraKeysView#reload(ExtraKeysInfo, float)} API. No-op if the view is null.
     *
     * @param extraKeysView the terminal's extra-keys view
     */
    public static void apply(ExtraKeysView extraKeysView) {
        if (extraKeysView == null) {
            return;
        }
        try {
            ExtraKeysInfo info = new ExtraKeysInfo(
                    DEFAULT_LAYOUT,
                    "default",
                    new ExtraKeysConstants.ExtraKeyDisplayMap());
            extraKeysView.reload(info, 0f);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load IIAB custom extra keys", e);
        }
    }
}
