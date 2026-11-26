/*
 * ============================================================================
 * Name        : SchoolFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School trust: Settings > School. Create the school (device zero), save and
 *               restore the backup of the school key (phase 3 of the school trust design).
 * ============================================================================
 */
package org.appdevforall.k2go.school.trust.presentation;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.datepicker.CalendarConstraints;
import com.google.android.material.datepicker.DateValidatorPointForward;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.school.trust.domain.Base64Url;
import org.appdevforall.k2go.school.trust.domain.JoinSchool;
import org.appdevforall.k2go.school.trust.domain.SchoolRing;
import org.appdevforall.k2go.school.trust.domain.SchoolSetup;
import org.appdevforall.k2go.school.trust.domain.TrustKeys;
import org.appdevforall.k2go.ui.K2GoHeaderView;
import org.appdevforall.k2go.ui.K2GoInputs;
import org.appdevforall.k2go.ui.K2GoText;
import org.appdevforall.k2go.ui.dialog.BrandDialog;
import org.appdevforall.k2go.util.Snackbars;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class SchoolFragment extends Fragment {

    private static final String KEY_CREATING = "creating";
    private static final String KEY_NAME = "name";
    private static final String KEY_UNTIL = "until";
    private static final long DAY = 86400;

    private SchoolViewModel vm;
    private RingViewModel ringVm;
    // The list of rings on the Wi-Fi shows (Join a school), on a phone that is not part of a school.
    private boolean joining;
    private LinearLayout list;
    private ActivityResultLauncher<String> saveBackup;
    private ActivityResultLauncher<String[]> openBackup;
    // The create form, while it shows. Passwords are not kept over a rotation.
    private boolean creating;
    private String formName = "";
    private long formUntil;   // seconds, the last second of the chosen day (UTC); 0: no date yet

    @Override
    public void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        SchoolViewModelFactory factory = new SchoolViewModelFactory(requireContext());
        vm = new ViewModelProvider(this, factory).get(SchoolViewModel.class);
        ringVm = new ViewModelProvider(this, factory).get(RingViewModel.class);
        saveBackup = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/octet-stream"), uri -> {
            if (uri != null) vm.writeBackup(uri);
        });
        openBackup = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) askRestorePassword(uri);
        });
        if (s != null) {
            creating = s.getBoolean(KEY_CREATING);
            formName = s.getString(KEY_NAME, "");
            formUntil = s.getLong(KEY_UNTIL);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean(KEY_CREATING, creating);
        out.putString(KEY_NAME, formName);
        out.putLong(KEY_UNTIL, formUntil);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup c, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_settings_sub, c, false);
        ((K2GoHeaderView) root.findViewById(R.id.k2go_sub_header)).setTitle(getString(R.string.k2go_settings_school));
        list = root.findViewById(R.id.k2go_sub_list);
        vm.state().observe(getViewLifecycleOwner(), this::render);
        vm.notice().observe(getViewLifecycleOwner(), n -> {
            if (n != null && n.take()) onNotice(root, n);
        });
        ringVm.ring().observe(getViewLifecycleOwner(), r -> rerender());
        ringVm.found().observe(getViewLifecycleOwner(), f -> rerender());
        ringVm.busy().observe(getViewLifecycleOwner(), b -> rerender());
        ringVm.waiting().observe(getViewLifecycleOwner(), w -> rerender());
        ringVm.event().observe(getViewLifecycleOwner(), e -> {
            if (e != null && e.take()) onRingEvent(root, e);
        });
        vm.load();
        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (joining) ringVm.startFind();
    }

    @Override
    public void onStop() {
        ringVm.stopFind();   // no search while the screen is not in front (battery)
        super.onStop();
    }

    private void rerender() {
        render(vm.state().getValue());
    }

    private void render(SchoolViewModel.State st) {
        if (list == null || st == null) return;
        Context ctx = requireContext();
        list.removeAllViews();
        RingViewModel.Host host = ringVm.ring().getValue();
        if (host != null) {
            renderRing(ctx, host);
        } else if (joining && st.kind != SchoolViewModel.State.Kind.DEVICE_ZERO && st.teacherLabel == null) {
            renderJoin(ctx);
        } else {
            list.addView(K2GoText.body(ctx, getString(R.string.k2go_school_intro), R.color.k2go_muted));
            switch (st.kind) {
                case DEVICE_ZERO:
                    creating = false;
                    renderDeviceZero(ctx, st);
                    break;
                case MEMBER:
                    creating = false;
                    renderMember(ctx, st);
                    break;
                case NONE:
                default:
                    if (creating) renderCreate(ctx, st.busy);
                    else renderNone(ctx, st.busy);
            }
        }
        // Room under the last item so the floating feedback button never covers it (as in About).
        list.addView(new View(ctx), new LinearLayout.LayoutParams(-1, dp(ctx, 96)));
    }

    private void renderNone(Context ctx, boolean busy) {
        list.addView(card(ctx, getString(R.string.k2go_school_none)));
        MaterialButton join = button(ctx, R.string.k2go_school_join, true);
        join.setOnClickListener(v -> {
            joining = true;
            ringVm.startFind();
            rerender();
        });
        list.addView(join, top(ctx, 16));
        join.setEnabled(!busy);
        MaterialButton create = button(ctx, R.string.k2go_school_create, false);
        create.setOnClickListener(v -> {
            creating = true;
            render(vm.state().getValue());
        });
        list.addView(create, top(ctx, 8));
        MaterialButton restore = button(ctx, R.string.k2go_school_restore, false);
        restore.setOnClickListener(v -> openBackup.launch(new String[] {"*/*"}));
        list.addView(restore, top(ctx, 8));
        create.setEnabled(!busy);
        restore.setEnabled(!busy);
        if (busy) list.addView(working(ctx));
    }

    private void renderCreate(Context ctx, boolean busy) {
        list.addView(K2GoInputs.fieldLabel(ctx, getString(R.string.k2go_school_name), ""), top(ctx, 16));
        final EditText name = K2GoInputs.textInput(ctx);
        name.setText(formName);
        list.addView(name);

        list.addView(K2GoInputs.fieldLabel(ctx, getString(R.string.k2go_school_year_end), ""), top(ctx, 8));
        final MaterialButton date = button(ctx, 0, false);
        date.setText(formUntil > 0 ? dateText(formUntil) : getString(R.string.k2go_school_year_end_pick));
        date.setOnClickListener(v -> pickDate());
        list.addView(date);

        list.addView(K2GoInputs.fieldLabel(ctx, getString(R.string.k2go_school_password), ""), top(ctx, 8));
        final EditText pw = K2GoInputs.passwordInput(ctx);
        list.addView(pw);
        list.addView(K2GoInputs.fieldLabel(ctx, getString(R.string.k2go_school_password_again), ""));
        final EditText again = K2GoInputs.passwordInput(ctx);
        list.addView(again);

        // Live checks, as under "Change password" (the same translated sentences).
        final TextView ruleLength = K2GoText.label(ctx, "", R.color.k2go_muted);
        list.addView(ruleLength, top(ctx, 8));
        final TextView ruleSame = K2GoText.label(ctx, "", R.color.k2go_muted);
        list.addView(ruleSame, top(ctx, 4));
        list.addView(K2GoText.label(ctx, getString(R.string.k2go_school_password_note), R.color.k2go_muted), top(ctx, 12));

        final MaterialButton create = button(ctx, R.string.k2go_school_create_button, true);
        list.addView(create, top(ctx, 16));
        MaterialButton cancel = button(ctx, R.string.k2go_cancel, false);
        cancel.setOnClickListener(v -> {
            creating = false;
            render(vm.state().getValue());
        });
        list.addView(cancel, top(ctx, 8));

        final Runnable check = () -> {
            formName = name.getText().toString();
            String p = pw.getText().toString();
            boolean longOk = p.codePointCount(0, p.length()) >= SchoolSetup.MIN_PASSWORD;
            boolean sameOk = !p.isEmpty() && p.equals(again.getText().toString());
            mark(ctx, ruleLength, longOk, getResources().getQuantityString(
                    R.plurals.k2go_auth_pw_rule_length, SchoolSetup.MIN_PASSWORD, SchoolSetup.MIN_PASSWORD));
            mark(ctx, ruleSame, sameOk, getString(R.string.k2go_auth_pw_rule_match));
            create.setEnabled(!busy && longOk && sameOk && !formName.trim().isEmpty() && formUntil > 0);
        };
        TextWatcher onType = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int n) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int n) { }
            @Override public void afterTextChanged(Editable e) { check.run(); }
        };
        name.addTextChangedListener(onType);
        pw.addTextChangedListener(onType);
        again.addTextChangedListener(onType);
        check.run();
        create.setOnClickListener(v -> vm.create(name.getText().toString(), formUntil,
                pw.getText().toString(), again.getText().toString()));
        name.setEnabled(!busy);
        pw.setEnabled(!busy);
        again.setEnabled(!busy);
        date.setEnabled(!busy);
        cancel.setEnabled(!busy);
        if (busy) list.addView(working(ctx));
    }

    private void renderDeviceZero(Context ctx, SchoolViewModel.State st) {
        list.addView(card(ctx, getString(R.string.k2go_school_device_zero, st.name)));
        list.addView(info(ctx, getString(R.string.k2go_school_id), st.school), top(ctx, 8));
        list.addView(info(ctx, getString(R.string.k2go_school_until), dateText(st.until)), top(ctx, 8));
        if (st.teacherLabel != null) {
            list.addView(info(ctx, getString(R.string.k2go_school_self_teacher), st.teacherLabel), top(ctx, 8));
        }
        boolean ringBusy = Boolean.TRUE.equals(ringVm.busy().getValue());
        MaterialButton approve = button(ctx, R.string.k2go_school_approve_teachers, true);
        approve.setOnClickListener(v -> askPassword(R.string.k2go_school_unlock_button, ringVm::openTeacherRing));
        approve.setEnabled(!ringBusy);
        list.addView(approve, top(ctx, 16));
        MaterialButton enroll = button(ctx, R.string.k2go_school_enroll_students, false);
        enroll.setOnClickListener(v -> ringVm.openStudentRing());
        enroll.setEnabled(!ringBusy);
        list.addView(enroll, top(ctx, 8));
        if (st.teacherLabel == null) {
            MaterialButton self = button(ctx, R.string.k2go_school_self_teacher, false);
            self.setOnClickListener(v -> askLabel(label -> askPassword(R.string.k2go_school_self_teacher,
                    pw -> ringVm.selfTeacher(pw, label))));
            self.setEnabled(!ringBusy);
            list.addView(self, top(ctx, 8));
        }
        if (ringBusy) list.addView(working(ctx));
        MaterialButton backup = button(ctx, R.string.k2go_school_backup, false);
        backup.setOnClickListener(v -> saveBackup.launch(vm.backupName()));
        list.addView(backup, top(ctx, 24));
        list.addView(K2GoText.label(ctx, getString(R.string.k2go_school_backup_hint), R.color.k2go_muted), top(ctx, 8));
    }

    private void renderMember(Context ctx, SchoolViewModel.State st) {
        if (st.teacherLabel == null) {
            list.addView(card(ctx, getString(R.string.k2go_school_member, st.name)));
            // A student phone of this school can still join a teacher ring of the same school.
            MaterialButton join = button(ctx, R.string.k2go_school_join, false);
            join.setOnClickListener(v -> {
                joining = true;
                ringVm.startFind();
                rerender();
            });
            list.addView(join, top(ctx, 16));
            return;
        }
        list.addView(card(ctx, getString(R.string.k2go_school_teacher_member, st.name, st.teacherLabel)));
        MaterialButton enroll = button(ctx, R.string.k2go_school_enroll_students, true);
        enroll.setOnClickListener(v -> ringVm.openStudentRing());
        enroll.setEnabled(!Boolean.TRUE.equals(ringVm.busy().getValue()));
        list.addView(enroll, top(ctx, 16));
    }

    /** The ring of this phone: the PIN for the board, the time left, and (teacher ring) the requests. */
    private void renderRing(Context ctx, RingViewModel.Host h) {
        boolean teacher = h.kind == SchoolRing.Kind.TEACHER;
        list.addView(K2GoText.title(ctx, getString(teacher ? R.string.k2go_school_ring_teacher_title
                : R.string.k2go_school_ring_student_title), R.color.k2go_ink, true));
        TextView pin = K2GoText.display(ctx, h.pin, R.color.k2go_ink);
        pin.setGravity(Gravity.CENTER);
        pin.setLetterSpacing(0.3f);
        list.addView(pin, top(ctx, 16));
        list.addView(K2GoText.label(ctx, getString(R.string.k2go_school_ring_pin_hint), R.color.k2go_muted), top(ctx, 8));
        long sec = Math.max(0, h.view.closesInMs / 1000);
        list.addView(K2GoText.label(ctx, getString(R.string.k2go_school_ring_closes,
                String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60)), R.color.k2go_muted), top(ctx, 4));
        if (teacher) {
            if (h.view.requests.isEmpty()) list.addView(card(ctx, getString(R.string.k2go_school_ring_none_asked)));
            int n = 0;
            for (SchoolRing.Request r : h.view.requests) list.addView(requestRow(ctx, ++n, r), top(ctx, 8));
        }
        MaterialButton close = button(ctx, R.string.k2go_school_close_ring, false);
        close.setOnClickListener(v -> ringVm.closeRing());
        list.addView(close, top(ctx, 24));
    }

    private View requestRow(Context ctx, int n, SchoolRing.Request r) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.k2go_card_bg);
        int p16 = dp(ctx, 16);
        row.setPadding(p16, p16, p16, p16);
        row.addView(K2GoText.body(ctx, getString(R.string.k2go_school_request_row, n, keyStart(r.key)), R.color.k2go_ink));
        if (r.approved || r.refused) {
            row.addView(K2GoText.label(ctx, getString(r.approved ? R.string.k2go_school_approved : R.string.k2go_school_refused),
                    r.approved ? R.color.k2go_ok_ink : R.color.k2go_muted));
            return row;
        }
        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        MaterialButton ok = button(ctx, R.string.k2go_school_approve, true);
        ok.setOnClickListener(v -> askLabel(label -> ringVm.approve(r, label)));
        MaterialButton no = button(ctx, R.string.k2go_school_refuse, false);
        no.setOnClickListener(v -> ringVm.refuse(r));
        buttons.addView(ok, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1f);
        right.leftMargin = dp(ctx, 8);
        buttons.addView(no, right);
        row.addView(buttons, top(ctx, 12));
        return row;
    }

    /** Join a school: the rings on the Wi-Fi, or the wait for device zero. */
    private void renderJoin(Context ctx) {
        list.addView(K2GoText.body(ctx, getString(R.string.k2go_school_join_hint), R.color.k2go_muted));
        if (Boolean.TRUE.equals(ringVm.waiting().getValue())) {
            list.addView(card(ctx, getString(R.string.k2go_school_waiting)));
            String key = ringVm.waitingKey();
            if (key != null) {
                list.addView(K2GoText.label(ctx, getString(R.string.k2go_school_own_key, keyStart(key)), R.color.k2go_muted), top(ctx, 8));
            }
            list.addView(working(ctx, ""));
            MaterialButton cancel = button(ctx, R.string.k2go_cancel, false);
            cancel.setOnClickListener(v -> ringVm.cancelWaiting());
            list.addView(cancel, top(ctx, 16));
            return;
        }
        List<SchoolRing.Found> all = ringVm.found().getValue();
        if (all == null || all.isEmpty()) {
            list.addView(working(ctx, getString(R.string.k2go_school_rings_looking)));
        } else {
            boolean busy = Boolean.TRUE.equals(ringVm.busy().getValue());
            for (SchoolRing.Found r : all) {
                boolean teacher = r.kind == SchoolRing.Kind.TEACHER;
                MaterialButton b = button(ctx, 0, false);
                b.setText(getString(teacher ? R.string.k2go_school_ring_row_teacher : R.string.k2go_school_ring_row_student, r.schoolName));
                b.setEnabled(!busy);
                b.setOnClickListener(v -> askPin(r));
                list.addView(b, top(ctx, 8));
                if (RingViewModel.twoOfSame(all, r)) {
                    list.addView(K2GoText.label(ctx, getString(R.string.k2go_school_ring_two), R.color.k2go_clay), top(ctx, 4));
                }
            }
            if (busy) list.addView(working(ctx));
        }
        MaterialButton back = button(ctx, R.string.k2go_cancel, false);
        back.setOnClickListener(v -> {
            joining = false;
            ringVm.stopFind();
            rerender();
        });
        list.addView(back, top(ctx, 24));
    }

    /** The start of the fingerprint of a key: device zero and the teacher phone show the same one. */
    private static String keyStart(String keyB64) {
        byte[] spki = Base64Url.decodeStandard(keyB64);
        return spki == null ? "?" : TrustKeys.schoolId(spki).substring(0, 8);
    }

    private interface OnText { void on(String text); }

    private void askPassword(int buttonRes, OnText then) {
        Context ctx = requireContext();
        final EditText pw = K2GoInputs.passwordInput(ctx);
        new BrandDialog(ctx)
                .setTitle(R.string.k2go_school_unlock_title)
                .setContentView(pw)
                .setPositive(buttonRes, () -> then.on(pw.getText().toString()))
                .setNegative(R.string.k2go_cancel, null)
                .show();
    }

    private void askLabel(OnText then) {
        Context ctx = requireContext();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        final EditText label = K2GoInputs.textInput(ctx);
        label.setFilters(new InputFilter[] {new InputFilter.LengthFilter(40)});
        box.addView(label);
        box.addView(K2GoText.label(ctx, getString(R.string.k2go_school_label_hint), R.color.k2go_muted), top(ctx, 8));
        new BrandDialog(ctx)
                .setTitle(R.string.k2go_school_label_title)
                .setContentView(box)
                .setPositive(R.string.k2go_school_approve, () -> {
                    String t = label.getText().toString().trim();
                    if (!t.isEmpty()) then.on(t);
                })
                .setNegative(R.string.k2go_cancel, null)
                .show();
    }

    private void askPin(SchoolRing.Found r) {
        Context ctx = requireContext();
        final EditText pin = K2GoInputs.textInput(ctx);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setFilters(new InputFilter[] {new InputFilter.LengthFilter(4)});
        new BrandDialog(ctx)
                .setTitle(R.string.k2go_school_pin_title)
                .setContentView(pin)
                .setPositive(R.string.k2go_school_join_button, () -> ringVm.join(r, pin.getText().toString()))
                .setNegative(R.string.k2go_cancel, null)
                .show();
    }

    private void onRingEvent(View root, RingViewModel.Event e) {
        switch (e.kind) {
            case WRONG_PASSWORD: snack(root, getString(R.string.k2go_school_err_password)); break;
            case OPEN_FAILED: snack(root, getString(R.string.k2go_school_err_ring)); break;
            case CLOSED:
                snack(root, getString("guesses".equals(e.closedBy) ? R.string.k2go_school_ring_closed_guesses
                        : R.string.k2go_school_ring_closed_time));
                break;
            case SELF_TEACHER:
                if (e.result != JoinSchool.Result.OK) snack(root, getString(R.string.k2go_school_err_failed));
                vm.load();
                break;
            case JOIN:
            default:
                if (e.result == JoinSchool.Result.OK) {
                    joining = false;
                    ringVm.stopFind();
                    snack(root, getString(R.string.k2go_school_joined, e.schoolName));
                    vm.load();
                } else {
                    snack(root, joinText(e));
                }
        }
    }

    private String joinText(RingViewModel.Event e) {
        switch (e.result) {
            case WRONG_PIN: return getString(R.string.k2go_school_err_wrong_pin);
            case TOO_MANY_TRIES: return getString(R.string.k2go_school_err_tries);
            case NO_RING: return getString(R.string.k2go_school_err_no_ring);
            case REFUSED: return getString(R.string.k2go_school_err_refused);
            case BAD_ANSWER: return getString(R.string.k2go_school_err_bad_answer);
            case NO_KEY: return getString(R.string.k2go_school_err_no_key);
            case OTHER_SCHOOL: {
                SchoolViewModel.State st = vm.state().getValue();
                return getString(R.string.k2go_school_err_other_school, st != null && st.name != null ? st.name : "");
            }
            default: return getString(R.string.k2go_school_err_failed);
        }
    }

    /** The end of the school year: today or later, at most two years ahead (SchoolSetup checks it too). */
    private void pickDate() {
        long nowMs = System.currentTimeMillis();
        if (formUntil > 0 && formUntil * 1000 < nowMs) formUntil = 0;   // a day kept from before is over
        CalendarConstraints limits = new CalendarConstraints.Builder()
                .setStart(nowMs)
                .setEnd(nowMs + SchoolSetup.MAX_YEAR_SECONDS * 1000)
                .setValidator(DateValidatorPointForward.now())
                .build();
        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.k2go_school_year_end)
                .setCalendarConstraints(limits)
                .setSelection(formUntil > 0 ? (formUntil - DAY + 1) * 1000 : nowMs + 300 * DAY * 1000)
                .build();
        picker.addOnPositiveButtonClickListener(sel -> {
            if (sel == null) return;
            formUntil = sel / 1000 + DAY - 1;   // the picker gives the day at 00:00 UTC: keep the whole day
            render(vm.state().getValue());
        });
        picker.show(getChildFragmentManager(), "school-year-end");
    }

    private void askRestorePassword(Uri file) {
        Context ctx = requireContext();
        final EditText pw = K2GoInputs.passwordInput(ctx);
        new BrandDialog(ctx)
                .setTitle(R.string.k2go_school_restore_title)
                .setContentView(pw)
                .setPositive(R.string.k2go_school_restore_button, () -> vm.restore(file, pw.getText().toString()))
                .setNegative(R.string.k2go_cancel, null)
                .show();
    }

    private void onNotice(View root, SchoolViewModel.Notice n) {
        switch (n.kind) {
            case CREATED:
                new BrandDialog(requireContext())
                        .setTitle(R.string.k2go_school_backup_ask_title)
                        .setMessage(R.string.k2go_school_backup_ask_msg)
                        .setPositive(R.string.k2go_school_backup_now, () -> saveBackup.launch(vm.backupName()))
                        .setNegative(R.string.k2go_school_backup_later, null)
                        .show();
                break;
            case RESTORED:     snack(root, getString(R.string.k2go_school_restored)); break;
            case BACKUP_SAVED: snack(root, getString(R.string.k2go_school_backup_saved)); break;
            case BACKUP_FAILED: snack(root, getString(R.string.k2go_school_backup_failed)); break;
            case RESULT:
            default:           snack(root, resultText(n)); break;
        }
    }

    private String resultText(SchoolViewModel.Notice n) {
        switch (n.result) {
            case NAME: return getString(R.string.k2go_school_err_name);
            case DATE: return getString(R.string.k2go_school_err_date);
            case PASSWORD_SHORT: return getResources().getQuantityString(
                    R.plurals.k2go_auth_pw_rule_length, SchoolSetup.MIN_PASSWORD, SchoolSetup.MIN_PASSWORD);
            case PASSWORD_DIFFERENT: return getString(R.string.k2go_auth_pw_rule_match);
            case ALREADY_IN_SCHOOL:
            case OTHER_SCHOOL: return getString(R.string.k2go_school_err_other_school, n.otherSchool == null ? "" : n.otherSchool);
            case NOT_A_BACKUP: return getString(R.string.k2go_school_err_not_backup);
            case WRONG_PASSWORD: return getString(R.string.k2go_school_err_password);
            case FAILED:
            default: return getString(R.string.k2go_school_err_failed);
        }
    }

    private static String dateText(long seconds) {
        DateFormat f = DateFormat.getDateInstance(DateFormat.MEDIUM);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));   // the picker works in UTC days
        return f.format(new Date(seconds * 1000));
    }

    private static void mark(Context ctx, TextView v, boolean met, String text) {
        v.setText((met ? "✓  " : "○  ") + text);
        v.setTextColor(ContextCompat.getColor(ctx, met ? R.color.k2go_ok_ink : R.color.k2go_muted));
    }

    private void snack(View anchor, String text) {
        Snackbars.make(anchor, text).show();
    }

    private View card(Context ctx, String text) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.k2go_card_bg);
        int p16 = dp(ctx, 16);
        box.setPadding(p16, p16, p16, p16);
        box.addView(K2GoText.body(ctx, text, R.color.k2go_ink));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(ctx, 16);
        box.setLayoutParams(lp);
        return box;
    }

    private View info(Context ctx, String title, String value) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.k2go_card_bg);
        int p16 = dp(ctx, 16);
        row.setPadding(p16, p16, p16, p16);
        row.addView(K2GoText.label(ctx, title, R.color.k2go_muted));
        row.addView(K2GoText.body(ctx, value, R.color.k2go_ink));
        return row;
    }

    private View working(Context ctx) {
        return working(ctx, getString(R.string.k2go_school_working));
    }

    private View working(Context ctx, String text) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        CircularProgressIndicator p = new CircularProgressIndicator(ctx);
        p.setIndeterminate(true);
        box.addView(p, new LinearLayout.LayoutParams(-2, -2));
        TextView t = K2GoText.label(ctx, text, R.color.k2go_muted);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.leftMargin = dp(ctx, 12);
        box.addView(t, tlp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(ctx, 16);
        box.setLayoutParams(lp);
        return box;
    }

    private static MaterialButton button(Context ctx, int textRes, boolean filled) {
        MaterialButton b = new MaterialButton(new android.view.ContextThemeWrapper(ctx,
                filled ? R.style.ThemeOverlay_K2Go_Button_Filled : R.style.ThemeOverlay_K2Go_Button_Outlined), null);
        if (textRes != 0) b.setText(ctx.getString(textRes));
        return b;
    }

    private static LinearLayout.LayoutParams top(Context ctx, int dpTop) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(ctx, dpTop);
        return lp;
    }

    private static int dp(Context c, int v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }
}
