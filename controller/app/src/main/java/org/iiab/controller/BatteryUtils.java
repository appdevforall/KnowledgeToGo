/*
 * ============================================================================
 * Name        : BatteryUtils.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Manage battery permissions
 * ============================================================================
 */
package org.iiab.controller;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.activity.result.ActivityResultLauncher;
import org.iiab.controller.ui.dialog.BrandDialog;

public class BatteryUtils {

    // Previously at MainActivity
    public static void checkAndPromptOptimizations(Activity activity, ActivityResultLauncher<Intent> launcher) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(activity.getPackageName())) {
                String manufacturer = Build.MANUFACTURER.toLowerCase();
                String message = activity.getString(R.string.battery_opt_msg);

                if (manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("xiaomi")) {

                    if (manufacturer.contains("oppo") || manufacturer.contains("realme")) {
                        message += activity.getString(R.string.battery_opt_oppo_extra);
                    } else if (manufacturer.contains("xiaomi")) {
                        message += activity.getString(R.string.battery_opt_xiaomi_extra);
                    }

                    new BrandDialog(activity)
                            .setTitle(R.string.battery_opt_title)
                            .setMessage(message)
                            .setPositive(R.string.go_to_settings, () -> openBatterySettings(activity, manufacturer))
                            .setNegative(R.string.cancel, null)
                            .show();
                } else {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + activity.getPackageName()));
                    launcher.launch(intent);
                }
            }
        }
    }

    private static void openBatterySettings(Activity activity, String manufacturer) {
        boolean success = false;
        String packageName = activity.getPackageName();

        if (manufacturer.contains("oppo") || manufacturer.contains("realme")) {
            try {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"));
                activity.startActivity(intent);
                success = true;
            } catch (Exception e) {
                try {
                    Intent intent = new Intent();
                    intent.setComponent(new ComponentName("com.coloros.oppoguardelf", "com.coloros.oppoguardelf.Permission.BackgroundAllowAppListActivity"));
                    activity.startActivity(intent);
                    success = true;
                } catch (Exception e2) {
                }
            }
        } else if (manufacturer.contains("xiaomi")) {
            try {
                Intent intent = new Intent("miui.intent.action.APP_BATTERY_SAVER_SETTINGS");
                intent.setComponent(new ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"));
                intent.putExtra("package_name", packageName);
                intent.putExtra("package_label", activity.getString(R.string.app_name));
                activity.startActivity(intent);
                success = true;
            } catch (Exception e) {
            }
        }

        if (!success) {
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + packageName));
                activity.startActivity(intent);
            } catch (Exception ex) {
            }
        }
    }
}