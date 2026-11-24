/*
 * ============================================================================
 * Name        : ClassEntry.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School: the "Teach a class" and "Join a class" entry on the Connect screen.
 * ============================================================================
 */
package org.appdevforall.k2go.school.presentation;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.fragment.app.Fragment;

import com.google.android.material.snackbar.Snackbar;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import org.appdevforall.k2go.PortalActivity;
import org.appdevforall.k2go.R;
import org.appdevforall.k2go.config.BoxEndpoints;
import org.appdevforall.k2go.redesign.CodeText;
import org.appdevforall.k2go.school.domain.ClassLinks;
import org.appdevforall.k2go.util.SnackbarDuration;

/**
 * Opens the class pages of the teacher box in the portal WebView: one APK, two roles. The teacher
 * opens its own box; a student scans the teacher's Connect "Open" code. The pages and the live
 * channel live on the box (dash-node), so this entry holds no class state.
 */
public final class ClassEntry {

    private final Fragment host;
    private final ActivityResultLauncher<ScanOptions> scan;

    /** Call from {@code Fragment.onCreate}: the scan launcher must be registered before the view. */
    public ClassEntry(Fragment host) {
        this.host = host;
        this.scan = host.registerForActivityResult(new ScanContract(), r -> onScan(r.getContents()));
    }

    /** Wire the buttons of {@code view_k2go_class_entry} inside {@code view}. */
    public void attach(View view) {
        View root = view.findViewById(R.id.k2go_class_entry);
        root.findViewById(R.id.k2go_class_teach).setOnClickListener(
                v -> open(ClassLinks.teacherUrl(BoxEndpoints.BASE)));
        root.findViewById(R.id.k2go_class_join).setOnClickListener(
                v -> scan.launch(CodeText.scanOptions(host.getString(R.string.k2go_class_scan_prompt))));
    }

    private void onScan(String contents) {
        if (contents == null || !host.isAdded()) return;   // scan cancelled, or the screen is gone
        String url = ClassLinks.studentUrlFromCode(contents, BoxEndpoints.PORT);
        if (url == null) {
            // The current view: the one from attach() can be gone after the scanner was in front.
            View anchor = host.getView();
            if (anchor == null) return;
            String msg = host.getString(R.string.k2go_class_bad_code);
            Snackbar.make(anchor, msg, SnackbarDuration.millisForText(msg)).show();
            return;
        }
        // The teacher sees this name in the class list. It goes only to the teacher box, when the
        // student taps Join (K2G-P5).
        open(url + "?n=" + Uri.encode(deviceName(host.requireContext())));
    }

    private void open(String url) {
        Intent i = new Intent(host.requireContext(), PortalActivity.class);
        i.putExtra("TARGET_URL", url);
        host.startActivity(i);
    }

    /** The name the user gave the phone in Android settings, else the model. */
    private static String deviceName(Context ctx) {
        String name = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1   // DEVICE_NAME: API 25
                ? Settings.Global.getString(ctx.getContentResolver(), Settings.Global.DEVICE_NAME)
                : null;
        return (name == null || name.trim().isEmpty()) ? Build.MODEL : name.trim();
    }
}
