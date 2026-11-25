/*
 * ============================================================================
 * Name        : ClassViewModel.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School: state of the Class tab (can this phone teach a class now?).
 * ============================================================================
 */
package org.appdevforall.k2go.school.presentation;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import org.appdevforall.k2go.school.domain.ClassLinks;
import org.appdevforall.k2go.school.domain.TeacherPage;

import java.util.concurrent.Executor;

/**
 * The value is the last answer of the box, for the screen only. {@link #check()} asks the box
 * again each time the tab shows, so a module installed or removed meanwhile shows at once.
 */
public class ClassViewModel extends ViewModel {

    private final TeacherPage teacherPage;
    private final Executor io;
    // null while the first check runs.
    private final MutableLiveData<ClassLinks.Teach> teach = new MutableLiveData<>(null);

    public ClassViewModel(TeacherPage teacherPage, Executor io) {
        this.teacherPage = teacherPage;
        this.io = io;
    }

    public LiveData<ClassLinks.Teach> teach() { return teach; }

    public void check() {
        io.execute(() -> teach.postValue(ClassLinks.teachFromStatus(teacherPage.status())));
    }
}
