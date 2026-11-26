/*
 * ============================================================================
 * Name        : SchoolViewModel.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School trust: state of Settings > School (device zero, phase 3).
 * ============================================================================
 */
package org.appdevforall.k2go.school.trust.presentation;

import android.content.ContentResolver;
import android.net.Uri;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import org.appdevforall.k2go.school.trust.domain.SchoolSetup;
import org.appdevforall.k2go.school.trust.domain.SchoolTrustRepository;
import org.appdevforall.k2go.school.trust.domain.TrustRecords;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Executor;

/**
 * The files are the one owner of the trust state; this model reads them again after each action
 * and keeps only what the screen shows.
 */
public class SchoolViewModel extends ViewModel {

    /** What the screen shows. */
    public static final class State {
        public enum Kind { NONE, DEVICE_ZERO, MEMBER }

        public final Kind kind;
        public final String name;
        public final String school;
        public final long until;
        public final boolean busy;
        /** The room on the certificate of this teacher phone; null: not a teacher phone. */
        public final String teacherLabel;

        State(Kind kind, String name, String school, long until, boolean busy, String teacherLabel) {
            this.kind = kind;
            this.name = name;
            this.school = school;
            this.until = until;
            this.busy = busy;
            this.teacherLabel = teacherLabel;
        }

        State busy(boolean b) { return new State(kind, name, school, until, b, teacherLabel); }
    }

    /** A one-time message for the screen. take() is true only the first time. */
    public static final class Notice {
        public enum Kind { CREATED, RESULT, BACKUP_SAVED, BACKUP_FAILED, RESTORED }

        public final Kind kind;
        public final SchoolSetup.Result result;
        public final String otherSchool;
        private boolean taken;

        Notice(Kind kind, SchoolSetup.Result result, String otherSchool) {
            this.kind = kind;
            this.result = result;
            this.otherSchool = otherSchool;
        }

        public boolean take() {
            if (taken) return false;
            taken = true;
            return true;
        }
    }

    private final SchoolTrustRepository repo;
    private final SchoolSetup setup;
    private final ContentResolver resolver;
    private final Executor io;
    private final MutableLiveData<State> state = new MutableLiveData<>();
    private final MutableLiveData<Notice> notice = new MutableLiveData<>();

    public SchoolViewModel(SchoolTrustRepository repo, ContentResolver resolver, Executor io) {
        this.repo = repo;
        this.setup = new SchoolSetup(repo);
        this.resolver = resolver;
        this.io = io;
    }

    public LiveData<State> state() { return state; }

    public LiveData<Notice> notice() { return notice; }

    public void load() {
        io.execute(() -> state.postValue(read(false)));
    }

    private State read(boolean busy) {
        TrustRecords.Root root = repo.root();
        if (root == null) return new State(State.Kind.NONE, null, null, 0, busy, null);
        State.Kind kind = repo.vault() != null ? State.Kind.DEVICE_ZERO : State.Kind.MEMBER;
        TrustRecords.Teacher cert = repo.verifiedCert(root, repo.teacherCertToken(), System.currentTimeMillis() / 1000);
        return new State(kind, root.name, root.school, root.until, busy, cert == null ? null : cert.label);
    }

    private boolean startBusy() {
        State s = state.getValue();
        if (s == null || s.busy) return false;
        state.setValue(s.busy(true));
        return true;
    }

    /** until: seconds. Slow (the password key): off the main thread. */
    public void create(String name, long until, String password, String again) {
        if (!startBusy()) return;
        io.execute(() -> {
            SchoolSetup.Result r = setup.create(name, until, password, again, System.currentTimeMillis() / 1000);
            state.postValue(read(false));
            notice.postValue(r == SchoolSetup.Result.OK
                    ? new Notice(Notice.Kind.CREATED, r, null)
                    : new Notice(Notice.Kind.RESULT, r, otherSchoolName()));
        });
    }

    public void restore(Uri file, String password) {
        if (!startBusy()) return;
        io.execute(() -> {
            byte[] bytes = null;
            try (InputStream in = resolver.openInputStream(file)) {
                if (in != null) bytes = readAll(in, 16 * 1024);
            } catch (Exception ignored) {
                // a file that cannot be read is not a backup
            }
            SchoolSetup.Result r = setup.restore(bytes, password);
            state.postValue(read(false));
            notice.postValue(r == SchoolSetup.Result.OK
                    ? new Notice(Notice.Kind.RESTORED, r, null)
                    : new Notice(Notice.Kind.RESULT, r, otherSchoolName()));
        });
    }

    /** Writes the backup into the file that the user chose (microSD card, with the file picker). */
    public void writeBackup(Uri target) {
        io.execute(() -> {
            byte[] data = repo.backup();
            boolean ok = false;
            if (data != null) {
                try (OutputStream out = resolver.openOutputStream(target, "wt")) {
                    if (out != null) {
                        out.write(data);
                        out.flush();
                        ok = true;
                    }
                } catch (Exception ignored) {
                    // reported below
                }
            }
            notice.postValue(new Notice(ok ? Notice.Kind.BACKUP_SAVED : Notice.Kind.BACKUP_FAILED, null, null));
        });
    }

    /** The backup file name: one school, one name. */
    public String backupName() {
        State s = state.getValue();
        return "k2go-school-" + (s != null && s.school != null ? s.school : "key") + ".k2key";
    }

    /** At most {@code max} bytes; null when the file is longer (it is not a backup). */
    private static byte[] readAll(InputStream in, int max) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > max) return null;
        }
        return out.toByteArray();
    }

    private String otherSchoolName() {
        TrustRecords.Root root = repo.root();
        return root == null ? null : root.name;
    }
}
