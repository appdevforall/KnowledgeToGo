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
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import org.appdevforall.k2go.PortalActivity;
import org.appdevforall.k2go.R;
import org.appdevforall.k2go.config.BoxEndpoints;
import org.appdevforall.k2go.redesign.CodeText;
import org.appdevforall.k2go.redesign.K2GoBottomSheet;
import org.appdevforall.k2go.school.domain.ClassAge;
import org.appdevforall.k2go.school.domain.ClassLinks;
import org.appdevforall.k2go.school.domain.FoundClass;
import org.appdevforall.k2go.util.SnackbarDuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Opens the class pages of the teacher box in the portal WebView: one APK, two roles. "Teach a
 * class" names the new class (optional) and opens the teacher page of this phone's box; "Join a
 * class" joins the one class on the Wi-Fi, or opens "Choose a class" when there are two or more. The scan
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
    private TextView oneAge;
    private MaterialButton searchAgain;
    private MaterialButton joinButton;
    // The "name this class?" dialog while it shows. A rotation closes it; the step shows it again.
    private AlertDialog nameDialog;
    // "Choose a class" while it shows (views of the sheet; null when it is closed).
    private LinearLayout chooserRows;
    private TextView chooserNoMatch;
    private EditText chooserSearch;
    private BottomSheetDialog chooser;

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
        oneAge = v.findViewById(R.id.k2go_class_one_age);
        searchAgain = v.findViewById(R.id.k2go_class_search_again);
        joinButton = v.findViewById(R.id.k2go_class_join_button);
        searchAgain.setOnClickListener(x -> find.start());
        teach.setOnClickListener(x -> vm.teachTapped());
        joinButton.setOnClickListener(x -> onJoinButton());
        v.findViewById(R.id.k2go_class_join).setOnClickListener(
                x -> scan.launch(CodeText.scanOptions(getString(R.string.k2go_class_scan_prompt))));
        vm.teach().observe(getViewLifecycleOwner(), this::renderTeach);
        vm.step().observe(getViewLifecycleOwner(), this::onTeachStep);
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

    @Override
    public void onDestroyView() {
        if (nameDialog != null) nameDialog.dismiss();   // a dismiss is no answer: the step stays
        nameDialog = null;
        super.onDestroyView();
    }

    private void onTeachStep(@Nullable ClassViewModel.TeachStep step) {
        if (step == ClassViewModel.TeachStep.ASK_NAME) {
            askName();
        } else if (step == ClassViewModel.TeachStep.OPEN_PAGE) {
            vm.stepDone();
            open(ClassLinks.teacherUrl(BoxEndpoints.BASE));
        } else if (step == ClassViewModel.TeachStep.START_FAILED) {
            vm.stepDone();
            View anchor = getView();
            if (anchor == null) return;
            String msg = getString(R.string.k2go_class_start_failed);
            Snackbar.make(anchor, msg, SnackbarDuration.millisForText(msg)).show();
        }
    }

    /** "Would you like to name this class?": Start (with the name, or none) or Skip (no name). */
    private void askName() {
        if (nameDialog != null && nameDialog.isShowing()) return;
        View content = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_k2go_class_name, null);
        EditText input = content.findViewById(R.id.k2go_class_name_input);
        AlertDialog d = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.k2go_class_name_title)
                .setView(content)
                .setPositiveButton(R.string.k2go_class_name_start, (x, w) -> vm.startClass(input.getText().toString()))
                .setNegativeButton(R.string.k2go_class_name_skip, (x, w) -> vm.startClass(null))
                .setOnCancelListener(x -> vm.stepDone())   // Back or a tap outside: no class
                .create();
        input.setOnEditorActionListener((tv, action, ev) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            return true;
        });
        nameDialog = d;
        d.show();
    }

    private void renderFound() {
        List<ClassRow> found = find.classes().getValue();
        int n = found == null ? 0 : found.size();
        boolean searching = Boolean.TRUE.equals(find.searching().getValue());
        searchingBar.setVisibility(n == 0 && searching ? View.VISIBLE : View.GONE);
        searchAgain.setVisibility(n == 0 && !searching ? View.VISIBLE : View.GONE);
        joinButton.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        ClassAge oneClassAge = n == 1 ? found.get(0).age : null;
        oneAge.setVisibility(oneClassAge != null ? View.VISIBLE : View.GONE);
        if (oneClassAge != null) oneAge.setText(startedText(oneClassAge));
        if (n == 0) {
            findStatus.setText(searching ? R.string.k2go_class_finding : R.string.k2go_class_none_found);
        } else if (n == 1) {
            findStatus.setText(R.string.k2go_class_one_open);
            joinButton.setText(getString(R.string.k2go_class_found_item, found.get(0).found.code));
        } else {
            findStatus.setText(getResources().getQuantityString(R.plurals.k2go_class_many_open, n, n));
            joinButton.setText(R.string.k2go_class_choose);
        }
        if (n == 0 && chooser != null) chooser.dismiss();
        renderChooser();
    }

    private void onJoinButton() {
        List<ClassRow> found = find.classes().getValue();
        if (found == null || found.isEmpty()) return;
        if (found.size() == 1) join(found.get(0).found);
        else openChooser();
    }

    /** "Choose a class": a search field and a table of the open classes. It follows the list live. */
    private void openChooser() {
        if (chooser != null) return;
        chooser = K2GoBottomSheet.show(requireActivity(), getViewLifecycleOwner(), (dlg, content) -> {
            View sheet = LayoutInflater.from(content.getContext()).inflate(R.layout.sheet_k2go_class_choose, content, false);
            content.addView(sheet);
            chooserRows = sheet.findViewById(R.id.k2go_class_rows);
            chooserNoMatch = sheet.findViewById(R.id.k2go_class_no_match);
            chooserSearch = sheet.findViewById(R.id.k2go_class_search);
            chooserSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence t, int a, int b, int c) { }
                @Override public void onTextChanged(CharSequence t, int a, int b, int c) { }
                @Override public void afterTextChanged(Editable e) { renderChooser(); }
            });
        }, () -> {
            chooser = null;
            chooserRows = null;
            chooserNoMatch = null;
            chooserSearch = null;
        });
        renderChooser();
    }

    private void renderChooser() {
        if (chooserRows == null) return;
        List<ClassRow> found = find.classes().getValue();
        String query = chooserSearch.getText().toString();
        List<ClassRow> shown = new ArrayList<>();
        if (found != null) for (ClassRow r : found) if (r.matches(query)) shown.add(r);
        chooserNoMatch.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        // The same classes in the same order (only an age changed): the rows stay, so a press or the
        // screen reader focus on a row is not lost.
        if (!sameClasses(chooserRows, shown)) {
            chooserRows.removeAllViews();
            LayoutInflater inf = LayoutInflater.from(chooserRows.getContext());
            for (ClassRow r : shown) {
                View row = inf.inflate(R.layout.item_k2go_class_found, chooserRows, false);
                row.setTag(r.found.key);
                chooserRows.addView(row);
            }
        }
        for (int i = 0; i < shown.size(); i++) bindRow(chooserRows.getChildAt(i), shown.get(i));
    }

    private static boolean sameClasses(LinearLayout rows, List<ClassRow> shown) {
        if (rows.getChildCount() != shown.size()) return false;
        for (int i = 0; i < shown.size(); i++) {
            if (!shown.get(i).found.key.equals(rows.getChildAt(i).getTag())) return false;
        }
        return true;
    }

    private void bindRow(View row, ClassRow r) {
        ((TextView) row.findViewById(R.id.k2go_class_row_code)).setText(r.found.code);
        TextView name = row.findViewById(R.id.k2go_class_row_name);
        name.setText(r.name);
        name.setVisibility(r.name == null ? View.GONE : View.VISIBLE);
        TextView age = row.findViewById(R.id.k2go_class_row_age);
        age.setText(r.age == null ? null : agoText(r.age));
        age.setVisibility(r.age == null ? View.GONE : View.VISIBLE);
        row.setOnClickListener(x -> {
            if (chooser != null) chooser.dismiss();
            join(r.found);
        });
        // A screen reader says what a tap does: "Join class 4F7K".
        ViewCompat.replaceAccessibilityAction(row, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                getString(R.string.k2go_class_found_item, r.found.code), null);
    }

    /** The age as a sentence, under "There is one open class": "Started 2 minutes ago". */
    private String startedText(ClassAge a) {
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

    /** The age in the "Name and start" column of the table: "2 minutes ago". */
    private String agoText(ClassAge a) {
        switch (a.kind) {
            case JUST_NOW: return getString(R.string.k2go_class_ago_just_now);
            case MINUTES: return getResources().getQuantityString(R.plurals.k2go_class_ago_minutes, a.minutes, a.minutes);
            case ABOUT_MINUTES: return getResources().getQuantityString(R.plurals.k2go_class_ago_about_minutes, a.minutes, a.minutes);
            case ABOUT_ONE_HOUR: return getString(R.string.k2go_class_ago_about_one_hour);
            case ABOUT_HOUR_AND_HALF: return getString(R.string.k2go_class_ago_about_hour_and_half);
            case ABOUT_TWO_HOURS: return getString(R.string.k2go_class_ago_about_two_hours);
            default: return getString(R.string.k2go_class_ago_over_two_hours);
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
                : t == ClassLinks.Teach.STUDENT_PHONE ? R.string.k2go_class_student_phone
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
