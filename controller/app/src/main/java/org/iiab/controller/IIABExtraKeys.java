package org.iiab.controller;

import android.util.Log;

import com.termux.shared.termux.extrakeys.ExtraKeysConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.extrakeys.ExtraKeysView;

import org.json.JSONException;

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

    /**
     * Minimal one-row layout applied only if {@link #DEFAULT_LAYOUT} fails to load, so the
     * user still gets usable keys instead of none (finding K4).
     */
    static final String FALLBACK_LAYOUT =
            "[['ESC', 'TAB', 'CTRL', 'LEFT', 'DOWN', 'UP', 'RIGHT']]";

    private IIABExtraKeys() {
        // Utility class — no instances.
    }

    /**
     * Loads the IIAB default extra-keys layout into the given view via the public
     * {@link ExtraKeysView#reload(ExtraKeysInfo, float)} API. If the default layout fails
     * for any reason it falls back to {@link #FALLBACK_LAYOUT} so the terminal still shows
     * usable keys. No-op if the view is null.
     *
     * @param extraKeysView the terminal's extra-keys view
     */
    public static void apply(ExtraKeysView extraKeysView) {
        if (extraKeysView == null) {
            return;
        }
        if (tryLoad(extraKeysView, DEFAULT_LAYOUT)) {
            return;
        }
        Log.w(TAG, "IIAB default extra-keys layout failed to load; applying minimal fallback");
        if (tryLoad(extraKeysView, FALLBACK_LAYOUT)) {
            return;
        }
        Log.e(TAG, "Both IIAB and fallback extra-keys layouts failed to load; no extra keys applied");
    }

    private static boolean tryLoad(ExtraKeysView extraKeysView, String layout) {
        try {
            extraKeysView.reload(buildInfo(layout), 0f);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load extra-keys layout", e);
            return false;
        }
    }

    /**
     * Builds an {@link ExtraKeysInfo} from a layout string using the same parameters as
     * {@link #apply}. Package-private so unit tests can validate {@link #DEFAULT_LAYOUT}
     * and {@link #FALLBACK_LAYOUT} without needing an Android view (finding K5).
     *
     * @param layout the extra-keys layout string
     * @return the parsed {@link ExtraKeysInfo}
     * @throws JSONException if the layout string is malformed
     */
    static ExtraKeysInfo buildInfo(String layout) throws JSONException {
        return new ExtraKeysInfo(
                layout,
                "default",
                new ExtraKeysConstants.ExtraKeyDisplayMap());
    }
}
