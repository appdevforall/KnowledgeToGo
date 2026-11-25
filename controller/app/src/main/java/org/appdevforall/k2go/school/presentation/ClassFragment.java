/*
 * ============================================================================
 * Name        : ClassFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School: the Class tab ("Teach a class" and "Join a class").
 * ============================================================================
 */
package org.appdevforall.k2go.school.presentation;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import org.appdevforall.k2go.PortalActivity;
import org.appdevforall.k2go.R;
import org.appdevforall.k2go.config.BoxEndpoints;
import org.appdevforall.k2go.redesign.CodeText;
import org.appdevforall.k2go.school.domain.ClassLinks;
import org.appdevforall.k2go.school.domain.FoundClass;
import org.appdevforall.k2go.util.SnackbarDuration;

import java.util.List;

/**
 * Opens the class pages of the teacher box in the portal WebView: one APK, two roles. The teacher
 * opens its own box; a student taps a class that a teacher box announces on the Wi-Fi, or scans
 * the teacher's Connect "Open" code. The pages and the live channel live on the box (dash-node),
 * so this tab holds no class state. It does not touch the hotspot.
 */
public final class ClassFragment extends Fragment {

    private ActivityResultLauncher<ScanOptions> scan;
    private ClassViewModel vm;
    private MaterialButton teach;
    private TextView teachStatus;
    private LinearProgressIndicator searchingBar;
    private TextView findStatus;
    private LinearLayout foundList;

    @Override
    public void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        scan = registerForActivityResult(new ScanContract(), r -> onScan(r.getContents()));
        vm = new ViewModelProvider(this, new ClassViewModelFactory(requireContext())).get(ClassViewModel.class);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup parent, @Nullable Bundle s) {
        return inf.inflate(R.layout.fragment_k2go_class, parent, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        teach = v.findViewById(R.id.k2go_class_teach);
        teachStatus = v.findViewById(R.id.k2go_class_teach_status);
        searchingBar = v.findViewById(R.id.k2go_class_searching);
        findStatus = v.findViewById(R.id.k2go_class_find_status);
        foundList = v.findViewById(R.id.k2go_class_found);
        teach.setOnClickListener(x -> open(ClassLinks.teacherUrl(BoxEndpoints.BASE)));
        v.findViewById(R.id.k2go_class_join).setOnClickListener(
                x -> scan.launch(CodeText.scanOptions(getString(R.string.k2go_class_scan_prompt))));
        vm.teach().observe(getViewLifecycleOwner(), this::renderTeach);
        vm.classes().observe(getViewLifecycleOwner(), c -> renderFound());
        vm.searching().observe(getViewLifecycleOwner(), b -> renderFound());
    }

    @Override
    public void onStart() {
        super.onStart();
        vm.check();   // also after the portal or the scanner was in front
        vm.startFinding();
    }

    @Override
    public void onStop() {
        vm.stopFinding();   // no search while the tab is not on the screen (battery)
        super.onStop();
    }

    private void renderFound() {
        List<FoundClass> found = vm.classes().getValue();
        boolean none = found == null || found.isEmpty();
        boolean searching = Boolean.TRUE.equals(vm.searching().getValue());
        searchingBar.setVisibility(none && searching ? View.VISIBLE : View.GONE);
        findStatus.setVisibility(none ? View.VISIBLE : View.GONE);
        findStatus.setText(searching ? R.string.k2go_class_finding : R.string.k2go_class_none_found);
        foundList.removeAllViews();
        if (none) return;
        LayoutInflater inf = LayoutInflater.from(foundList.getContext());
        for (FoundClass c : found) {
            MaterialButton b = (MaterialButton) inf.inflate(R.layout.item_k2go_class_found, foundList, false);
            b.setText(getString(R.string.k2go_class_found_item, c.address));
            b.setOnClickListener(x -> join(c.studentUrl));
            foundList.addView(b);
        }
    }

    private void renderTeach(@Nullable ClassLinks.Teach t) {
        boolean available = t == ClassLinks.Teach.AVAILABLE;
        teach.setVisibility(available || t == null ? View.VISIBLE : View.GONE);
        teach.setEnabled(available);
        if (available) {
            teachStatus.setVisibility(View.GONE);
            return;
        }
        int msg = t == null ? R.string.k2go_class_checking
                : t == ClassLinks.Teach.NO_MODULE ? R.string.k2go_class_no_module
                : R.string.k2go_class_no_box;
        teachStatus.setText(msg);
        teachStatus.setVisibility(View.VISIBLE);
    }

    private void onScan(String contents) {
        if (contents == null || !isAdded()) return;   // scan cancelled, or the screen is gone
        String url = ClassLinks.studentUrlFromCode(contents, BoxEndpoints.PORT);
        if (url == null) {
            View anchor = getView();
            if (anchor == null) return;
            String msg = getString(R.string.k2go_class_bad_code);
            Snackbar.make(anchor, msg, SnackbarDuration.millisForText(msg)).show();
            return;
        }
        join(url);
    }

    private void join(String studentUrl) {
        // The teacher sees this name in the class list. It goes only to the teacher box, when the
        // student joins (K2G-P5).
        open(studentUrl + "?n=" + Uri.encode(deviceName(requireContext())));
    }

    private void open(String url) {
        Intent i = new Intent(requireContext(), PortalActivity.class);
        i.putExtra("TARGET_URL", url);
        startActivity(i);
    }

    /** The name the user gave the phone in Android settings, else the model. */
    private static String deviceName(Context ctx) {
        String name = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1   // DEVICE_NAME: API 25
                ? Settings.Global.getString(ctx.getContentResolver(), Settings.Global.DEVICE_NAME)
                : null;
        return (name == null || name.trim().isEmpty()) ? Build.MODEL : name.trim();
    }
}
