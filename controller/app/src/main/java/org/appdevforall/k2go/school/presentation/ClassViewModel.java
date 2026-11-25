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
import org.appdevforall.k2go.school.domain.TeacherPage;

import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/**
 * Two owners, one fact each. "The library runs" comes from the app's server state (one publisher,
 * the server tick); this model does not ask the box for it. "The library has the class module" has
 * no app owner: only the box knows it, so one GET of the teacher page answers it, each time the
 * library comes up and each time the tab shows. The value is for the screen only.
 */
public class ClassViewModel extends ViewModel {

    private final TeacherPage teacherPage;
    private final Executor io;
    private final LiveData<ServerState> server;
    private final BooleanSupplier observed;
    // null while the first check runs.
    private final MutableLiveData<ClassLinks.Teach> teach = new MutableLiveData<>(null);
    private final Observer<ServerState> onServer = s -> decide(false);
    private final Handler main = new Handler(Looper.getMainLooper());

    // Main thread only. lastUp: the library state of the last decision (null before the first).
    private Boolean lastUp;
    // A GET answer is shown only if no newer decision came while it ran.
    private int generation;

    public ClassViewModel(TeacherPage teacherPage, Executor io,
                          LiveData<ServerState> server, BooleanSupplier observed) {
        this.teacherPage = teacherPage;
        this.io = io;
        this.server = server;
        this.observed = observed;
        server.observeForever(onServer);
    }

    public LiveData<ClassLinks.Teach> teach() { return teach; }

    /** Asks the box again for the module, so a module installed or removed meanwhile shows. */
    public void check() { decide(true); }

    private void decide(boolean again) {
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
