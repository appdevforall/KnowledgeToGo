/*
 * ============================================================================
 * Name        : FindClassesViewModel.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School: the open classes on the Wi-Fi, for Class > Join.
 * ============================================================================
 */
package org.appdevforall.k2go.school.presentation;

import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import org.appdevforall.k2go.school.domain.ClassFinder;
import org.appdevforall.k2go.school.domain.ClassProbe;
import org.appdevforall.k2go.school.domain.FoundClass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Two owners. The network (mDNS) says which classes are announced; the box of each class says if
 * the class is still open, with that code. The list shows a class only when its box confirms it,
 * and checks the shown classes again every {@link #CHECK_MS}: a teacher phone that leaves the
 * Wi-Fi sends no goodbye, and the network keeps its announcement for about 2 minutes. The list
 * is for the screen only, and it is empty again at the next search.
 */
public class FindClassesViewModel extends ViewModel {

    /** After this time with no class found, the tab says that it found none. */
    static final long SEARCH_MS = 5000;
    static final long CHECK_MS = 5000;

    private final ClassFinder finder;
    private final ClassProbe probe;
    private final Executor io;
    private final Handler main = new Handler(Looper.getMainLooper());

    // Main thread only. Insertion order: a class stays at its place in the list.
    private final Map<String, FoundClass> shown = new LinkedHashMap<>();
    private final MutableLiveData<List<FoundClass>> classes = new MutableLiveData<>(Collections.emptyList());
    private final MutableLiveData<Boolean> searching = new MutableLiveData<>(false);
    // A probe answer of an older search changes nothing.
    private int round;
    private boolean running;

    private final Runnable searchEnd = () -> searching.setValue(false);
    private final Runnable check = this::checkShown;
    private final ClassFinder.Listener onFinder = new ClassFinder.Listener() {
        @Override public void onFound(FoundClass c) { confirm(c); }
        @Override public void onLost(String key) { if (shown.remove(key) != null) publish(); }
    };

    public FindClassesViewModel(ClassFinder finder, ClassProbe probe, Executor io) {
        this.finder = finder;
        this.probe = probe;
        this.io = io;
    }

    /** The open classes on the Wi-Fi, in the order they were found. */
    public LiveData<List<FoundClass>> classes() { return classes; }

    /** True during the first {@link #SEARCH_MS} of a search. */
    public LiveData<Boolean> searching() { return searching; }

    /** Starts to look for classes on the Wi-Fi. Call {@link #stop()} when the tab hides. */
    public void start() {
        stop();
        running = true;
        round++;
        shown.clear();
        publish();
        searching.setValue(true);
        main.postDelayed(searchEnd, SEARCH_MS);
        main.postDelayed(check, CHECK_MS);
        finder.start(onFinder);
    }

    public void stop() {
        running = false;
        main.removeCallbacks(searchEnd);
        main.removeCallbacks(check);
        searching.setValue(false);
        finder.stop();
    }

    private void confirm(FoundClass c) {
        final int r = round;
        io.execute(() -> {
            boolean open = c.code.equals(probe.codeAt(c.address));
            main.post(() -> {
                if (!running || r != round) return;
                if (open) shown.put(c.key, c);
                else shown.remove(c.key);
                publish();
            });
        });
    }

    private void checkShown() {
        if (!running) return;
        for (FoundClass c : new ArrayList<>(shown.values())) confirm(c);
        main.postDelayed(check, CHECK_MS);
    }

    private void publish() {
        classes.setValue(Collections.unmodifiableList(new ArrayList<>(shown.values())));
    }

    @Override
    protected void onCleared() {
        stop();
    }
}
