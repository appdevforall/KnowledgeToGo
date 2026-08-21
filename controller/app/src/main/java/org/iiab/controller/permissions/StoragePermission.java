package org.iiab.controller.permissions;

import android.app.Activity;
import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import androidx.activity.result.ActivityResultLauncher;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * ADFA-5225: single source of truth for the app's "broad storage access" permission.
 *
 * <p>The permission model differs by OS version, and each onboarding surface used to branch on
 * that independently — which is how a Samsung S9 (API &lt;= 29) ended up blocked: the wizard only
 * handled the Android 11+ path, so on older devices the grant simply never happened and onboarding
 * could not advance. Centralizing the check and the request keeps every surface consistent on
 * every OS.
 *
 * <ul>
 *   <li><b>Android 11+ (API 30, R):</b> "All files access" (MANAGE_EXTERNAL_STORAGE), granted from
 *       a system Settings screen.</li>
 *   <li><b>Android 10 and below (API &lt;= 29):</b> the legacy WRITE_EXTERNAL_STORAGE runtime
 *       permission, which under legacy-storage mode (targetSdk 28) grants broad shared-storage
 *       access — enough for the {@code /sdcard} bind into the proot environment.</li>
 * </ul>
 */
public final class StoragePermission {

    private StoragePermission() {}

    /** True when the app currently has broad storage access on this OS version. */
    public static boolean isGranted(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Trigger the correct grant flow for this OS version. No-op if the permission is already held.
     * Use this from a surface that already offers its own "open app settings" escape hatch (e.g. the
     * settings shell's "Manage all" button); the wizard, which has none, should use
     * {@link #requestOrOpenSettings} instead so a permanent pre-R denial can't dead-end onboarding.
     *
     * @param settingsLauncher launches the R+ "All files access" Settings screen (Intent contract)
     * @param runtimeLauncher  requests the pre-R WRITE_EXTERNAL_STORAGE runtime permission (String contract)
     */
    public static void request(Context ctx,
                               ActivityResultLauncher<Intent> settingsLauncher,
                               ActivityResultLauncher<String> runtimeLauncher) {
        if (isGranted(ctx)) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            launchAllFilesSettings(ctx, settingsLauncher);
        } else {
            runtimeLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
    }

    /**
     * Like {@link #request}, but on pre-R it falls back to the app's details settings when the
     * runtime permission has been permanently denied ("Don't ask again"). Without this the second
     * tap would silently no-op and re-strand the user — the exact ADFA-5225 symptom, on a screen
     * (the wizard) that has no other route to Settings.
     *
     * @param alreadyAsked whether the runtime permission has been requested before this call
     * @return the value the caller should store back as its "already asked" flag
     */
    public static boolean requestOrOpenSettings(Activity activity,
                                                ActivityResultLauncher<Intent> settingsLauncher,
                                                ActivityResultLauncher<String> runtimeLauncher,
                                                boolean alreadyAsked) {
        if (isGranted(activity)) return alreadyAsked;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            launchAllFilesSettings(activity, settingsLauncher);
            return alreadyAsked;
        }
        // Pre-R: a prior denial with rationale==false means "Don't ask again" — the dialog will
        // never show again, so send the user to app settings instead of firing a no-op request.
        boolean permanentlyDenied = alreadyAsked && !ActivityCompat
                .shouldShowRequestPermissionRationale(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (permanentlyDenied) {
            settingsLauncher.launch(appDetailsSettings(activity));
        } else {
            runtimeLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        return true;
    }

    /** The app's own "App info" settings page, where the user can toggle a denied permission. */
    public static Intent appDetailsSettings(Context ctx) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(Uri.parse("package:" + ctx.getPackageName()));
        return i;
    }

    private static void launchAllFilesSettings(Context ctx, ActivityResultLauncher<Intent> settingsLauncher) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            i.addCategory("android.intent.category.DEFAULT");
            i.setData(Uri.parse("package:" + ctx.getPackageName()));
            settingsLauncher.launch(i);
        } catch (Exception e) {
            settingsLauncher.launch(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }
}
