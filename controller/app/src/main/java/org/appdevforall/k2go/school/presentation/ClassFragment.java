/*
 * ============================================================================
 * Name        : ClassFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School: the Class tab ("Teach a class" and "Join a class").
 * ============================================================================
 */
package org.appdevforall.k2go.school.presentation;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
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
import org.appdevforall.k2go.school.domain.ClassAge;
import org.appdevforall.k2go.school.domain.ClassLinks;
import org.appdevforall.k2go.school.domain.FoundClass;
import org.appdevforall.k2go.util.SnackbarDuration;

import java.util.List;

/**
 * Opens the class pages of the teacher box in the portal WebView: one APK, two roles. The teacher
 * opens its own box; a student taps a class that a teacher box announces on the Wi-Fi. The scan
 * of the teacher's Connect "Open" code stays in the code, but its button is hidden for now (see
 * the layout). The pages and the live channel live on the box (dash-node),
 * so this tab holds no class state. It does not touch the hotspot.
 */
public final class ClassFragment extends Fragment {

    private ActivityResultLauncher<ScanOptions> scan;
    private ClassViewModel vm;
    private FindClassesViewModel find;
    private MaterialButton teach;
    private TextView teachStatus;
    private LinearProgressIndicator searchingBar;
    private TextView findStatus;
    private LinearLayout foundList;
    private MaterialButton searchAgain;

    @Override
    public void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        scan = registerForActivityResult(new ScanContract(), r -> onScan(r.getContents()));
        ClassViewModelFactory factory = new ClassViewModelFactory(requireContext());
        vm = new ViewModelProvider(this, factory).get(ClassViewModel.class);
        find = new ViewModelProvider(this, factory).get(FindClassesViewModel.class);
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
        searchAgain = v.findViewById(R.id.k2go_class_search_again);
        searchAgain.setOnClickListener(x -> find.start());
        teach.setOnClickListener(x -> open(ClassLinks.teacherUrl(BoxEndpoints.BASE)));
        v.findViewById(R.id.k2go_class_join).setOnClickListener(
                x -> scan.launch(CodeText.scanOptions(getString(R.string.k2go_class_scan_prompt))));
        vm.teach().observe(getViewLifecycleOwner(), this::renderTeach);
        find.classes().observe(getViewLifecycleOwner(), c -> renderFound());
        find.searching().observe(getViewLifecycleOwner(), b -> renderFound());
    }

    @Override
    public void onStart() {
        super.onStart();
        vm.check();   // also after the portal or the scanner was in front
        find.start();
    }

    @Override
    public void onStop() {
        find.stop();   // no search while the tab is not on the screen (battery)
        super.onStop();
    }

    private void renderFound() {
        List<ClassRow> found = find.classes().getValue();
        boolean none = found == null || found.isEmpty();
        boolean searching = Boolean.TRUE.equals(find.searching().getValue());
        searchingBar.setVisibility(none && searching ? View.VISIBLE : View.GONE);
        findStatus.setVisibility(none ? View.VISIBLE : View.GONE);
        searchAgain.setVisibility(none && !searching ? View.VISIBLE : View.GONE);
        findStatus.setText(searching ? R.string.k2go_class_finding : R.string.k2go_class_none_found);
        if (none) {
            foundList.removeAllViews();
            return;
        }
        // The same classes in the same order (only an age or a name changed): the rows stay, so a
        // press or the screen reader focus on a row is not lost.
        if (!sameClasses(found)) {
            foundList.removeAllViews();
            LayoutInflater inf = LayoutInflater.from(foundList.getContext());
            for (ClassRow r : found) {
                View row = inf.inflate(R.layout.item_k2go_class_found, foundList, false);
                row.setTag(r.found.key);
                foundList.addView(row);
            }
        }
        for (int i = 0; i < found.size(); i++) bind(foundList.getChildAt(i), found.get(i));
    }

    private boolean sameClasses(List<ClassRow> found) {
        if (foundList.getChildCount() != found.size()) return false;
        for (int i = 0; i < found.size(); i++) {
            if (!found.get(i).found.key.equals(foundList.getChildAt(i).getTag())) return false;
        }
        return true;
    }

    private void bind(View row, ClassRow r) {
        ((TextView) row.findViewById(R.id.k2go_class_row_code)).setText(r.found.code);
        TextView name = row.findViewById(R.id.k2go_class_row_name);
        name.setText(r.name);
        name.setVisibility(r.name == null ? View.GONE : View.VISIBLE);
        TextView age = row.findViewById(R.id.k2go_class_row_age);
        age.setText(r.age == null ? null : ageText(r.age));
        age.setVisibility(r.age == null ? View.GONE : View.VISIBLE);
        row.setOnClickListener(x -> join(r.found));
        // A screen reader says what a tap does: "Join class 4F7K".
        ViewCompat.replaceAccessibilityAction(row, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                getString(R.string.k2go_class_found_item, r.found.code), null);
    }

    private String ageText(ClassAge a) {
        switch (a.kind) {
            case JUST_NOW: return getString(R.string.k2go_class_age_just_now);
            case MINUTES: return getResources().getQuantityString(R.plurals.k2go_class_age_minutes, a.minutes, a.minutes);
            case ABOUT_MINUTES: return getResources().getQuantityString(R.plurals.k2go_class_age_about_minutes, a.minutes, a.minutes);
            case ABOUT_ONE_HOUR: return getString(R.string.k2go_class_age_about_one_hour);
            case ABOUT_HOUR_AND_HALF: return getString(R.string.k2go_class_age_about_hour_and_half);
            case ABOUT_TWO_HOURS: return getString(R.string.k2go_class_age_about_two_hours);
            default: return getString(R.string.k2go_class_age_over_two_hours);
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
        startActivity(portal(url));
    }

    /**
     * A class from the list: the student page, and what the screen needs to find it again. No
     * name and no device data go to the box: the box gives the phone a seat number (K2G-P5).
     */
    private void join(FoundClass c) {
        Intent i = portal(ClassLinks.withClass(c.studentUrl, c.code));
        ClassRejoin.putExtras(i, c);
        startActivity(i);
    }

    private void open(String url) {
        startActivity(portal(url));
    }

    private Intent portal(String url) {
        Intent i = new Intent(requireContext(), PortalActivity.class);
        i.putExtra("TARGET_URL", url);
        return i;
    }
}
