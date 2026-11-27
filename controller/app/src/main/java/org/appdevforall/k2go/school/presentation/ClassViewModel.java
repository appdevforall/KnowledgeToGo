/*
 * ============================================================================
 * Name        : ClassViewModel.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School: state of the Class tab (can this phone teach a class now?).
 * ============================================================================
 */
package org.appdevforall.k2go.school.presentation;

import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModel;

import org.appdevforall.k2go.ServerState;
import org.appdevforall.k2go.school.domain.ClassLinks;
import org.appdevforall.k2go.school.domain.ClassProbe;
import org.appdevforall.k2go.school.domain.ClassStarter;
import org.appdevforall.k2go.school.domain.SchoolRole;
import org.appdevforall.k2go.school.domain.TeacherPage;

import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Two owners, one fact each. "The library runs" comes from the app's server state (one publisher,
 * the server tick); this model does not ask the box for it. "The library has the class module" has
 * no app owner: only the box knows it, so one GET of the teacher page answers it, each time the
 * library comes up and each time the tab shows. The value is for the screen only. "This phone of a
 * school has no teacher certificate" comes from the school trust files (PhoneRole): read again each
 * time the tab shows, as the trust changes only on the School screen.
 */
public class ClassViewModel extends ViewModel {

    private final TeacherPage teacherPage;
    private final ClassProbe probe;
    private final ClassStarter starter;
    private final Executor io;
    private final LiveData<ServerState> server;
    private final BooleanSupplier observed;
    private final Supplier<SchoolRole> role;
    private final BooleanSupplier deviceZero;
    // null while the first check runs.
    private final MutableLiveData<ClassLinks.Teach> teach = new MutableLiveData<>(null);
    // The next step of "Teach a class", for the screen: null while there is none. The screen calls
    // stepDone() when it handled the step; a rotation shows the same step again.
    private final MutableLiveData<TeachStep> step = new MutableLiveData<>(null);
    // Main thread only: a box call of "Teach a class" runs (a second tap does nothing).
    private boolean pending;
    private final Observer<ServerState> onServer = s -> decide(false);
    private static final String LOCAL_BOX = "localhost";
    private final Handler main = new Handler(Looper.getMainLooper());

    // Main thread only. lastUp: the library state of the last decision (null before the first).
    private Boolean lastUp;
    // Main thread only: the role of the last read (each time the tab shows). null: the phone can teach.
    private ClassLinks.Teach noTeaching;
    private int roleRead;   // only the newest role read counts
    // Main thread only: the first role read ended. Before it, the tab shows "checking": the box answer
    // alone (403 on a student phone) would show a wrong reason for a moment on a slow phone.
    private boolean roleKnown;
    // A GET answer is shown only if no newer decision came while it ran.
    private int generation;

    public ClassViewModel(TeacherPage teacherPage, ClassProbe probe, ClassStarter starter, Executor io,
                          LiveData<ServerState> server, BooleanSupplier observed, Supplier<SchoolRole> role,
                          BooleanSupplier deviceZero) {
        this.teacherPage = teacherPage;
        this.probe = probe;
        this.starter = starter;
        this.io = io;
        this.server = server;
        this.observed = observed;
        this.role = role;
        this.deviceZero = deviceZero;
        server.observeForever(onServer);
    }

    public LiveData<ClassLinks.Teach> teach() { return teach; }

    /** The steps of "Teach a class". */
    public enum TeachStep {
        /** No class is open on this phone's box: ask the teacher for a name (optional). */
        ASK_NAME,
        /** Open the teacher page: its class is open, or the page opens one itself. */
        OPEN_PAGE,
        /** The box did not open the class: the teacher tries again. */
        START_FAILED
    }

    public LiveData<TeachStep> step() { return step; }

    /**
     * "Teach a class": an open class on this phone's box goes on (no question, no rename); with no
     * class open, the teacher names the new one first. The box answers; the app keeps no copy.
     */
    public void teachTapped() {
        if (pending || step.getValue() != null) return;   // a step runs or is on the screen
        pending = true;
        io.execute(() -> {
            TeachStep next = TeachStep.OPEN_PAGE;   // no answer: the page shows the state of the box
            try {
                ClassProbe.Answer a = probe.ask(LOCAL_BOX);
                if (a != null && a.code == null) next = TeachStep.ASK_NAME;
            } finally {
                TeachStep result = next;
                main.post(() -> {
                    pending = false;
                    step.setValue(result);
                });
            }
        });
    }

    /** The teacher answered the name question: {@code name} null or empty for no name. */
    public void startClass(String name) {
        step.setValue(null);
        pending = true;
        io.execute(() -> {
            TeachStep next = TeachStep.START_FAILED;
            try {
                // A class that was open already goes on with its own name.
                if (starter.start(name) != ClassStarter.Result.FAILED) next = TeachStep.OPEN_PAGE;
            } finally {
                TeachStep result = next;
                main.post(() -> {
                    pending = false;
                    step.setValue(result);
                });
            }
        });
    }

    /** The screen handled the step (opened the page, or the teacher closed the question). */
    public void stepDone() { step.setValue(null); }

    /**
     * Reads the role again (files, a signature check and the Keystore: off the main thread), then asks
     * the box again for the module, so a module installed or removed meanwhile shows.
     */
    public void check() {
        int mine = ++roleRead;
        io.execute(() -> {
            ClassLinks.Teach t = role.get().canTeach() ? null
                    : deviceZero.getAsBoolean() ? ClassLinks.Teach.DEVICE_ZERO : ClassLinks.Teach.STUDENT_PHONE;
            main.post(() -> {
                if (mine != roleRead) return;   // a newer read runs
                roleKnown = true;
                noTeaching = t;
                decide(true);
            });
        });
    }

    private void decide(boolean again) {
        if (!roleKnown) return;   // keep "checking" until the role is known
        if (noTeaching != null) {
            lastUp = null;   // a certificate that comes later decides again on the next tick
            ++generation;    // a GET that runs now does not show
            teach.setValue(noTeaching);
            return;
        }
        if (!observed.getAsBoolean()) return;   // the first tick did not run yet: keep "checking"
        ServerState s = server.getValue();
        boolean up = s != null && s.alive;
        if (!again && lastUp != null && lastUp == up) return;   // each tick posts, most change nothing
        lastUp = up;
        int gen = ++generation;
        if (!up) {
            teach.setValue(ClassLinks.Teach.NO_BOX);
            return;
        }
        io.execute(() -> {
            ClassLinks.Teach t = ClassLinks.teachFromStatus(teacherPage.status());
            postIfCurrent(gen, t);
        });
    }

    private void postIfCurrent(int gen, ClassLinks.Teach t) {
        main.post(() -> { if (gen == generation) teach.setValue(t); });   // generation: main thread
    }

    @Override
    protected void onCleared() {
        server.removeObserver(onServer);
    }
}
