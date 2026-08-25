package org.iiab.controller.install.presentation;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ADFA-5228: loads a module's ordered runrole task table from {@code assets/runrole/<role>.txt}
 * (one task name per line), seeded from the upstream build logs. Returns an empty list when there
 * is no table for the role (e.g. matomo until it has a log), so {@code RunroleProgress} stays
 * indeterminate and the bar falls back to a spinner.
 */
public final class RunroleTables {

    private static final String TAG = "K2Go-Runrole";

    private RunroleTables() {}

    public static List<String> tasksFor(Context ctx, String role) {
        if (ctx == null || role == null || role.isEmpty()) return Collections.emptyList();
        String path = "runrole/" + role + ".txt";
        List<String> out = new ArrayList<>();
        try (InputStream in = ctx.getAssets().open(path);
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (!t.isEmpty()) out.add(t);
            }
        } catch (Exception e) {
            Log.i(TAG, "no runrole table for '" + role + "' (" + path + ") — indeterminate bar");
            return Collections.emptyList();
        }
        return out;
    }
}
