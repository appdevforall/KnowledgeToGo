package org.iiab.controller.feedback.data;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import androidx.core.content.pm.PackageInfoCompat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Best-effort device + build diagnostics for a feedback report. Data layer. */
public final class FeedbackDiagnostics {

    private FeedbackDiagnostics() {
    }

    public static String appVersionName(Context ctx) {
        try {
            return pkg(ctx).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    public static int appVersionCode(Context ctx) {
        try {
            return (int) PackageInfoCompat.getLongVersionCode(pkg(ctx));
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /** Adopted native-binaries tag from the bundled asset, or null if unavailable. */
    public static String binariesTag(Context ctx) {
        try (InputStream is = ctx.getAssets().open("current_binaries_tag.txt");
             BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line = br.readLine();
            if (line == null) {
                return null;
            }
            line = line.trim();
            return line.isEmpty() ? null : line;
        } catch (IOException e) {
            return null;
        }
    }

    private static PackageInfo pkg(Context ctx) throws PackageManager.NameNotFoundException {
        return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
    }
}
