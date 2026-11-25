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
import android.os.SystemClock;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import org.appdevforall.k2go.school.domain.ClassFinder;
import org.appdevforall.k2go.school.domain.ClassProbe;
import org.appdevforall.k2go.school.domain.FoundClass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Two owners. The network (mDNS) says which classes are announced, at which addresses; the box of
 * each class says if the class is still open, with that code. The list shows a class only when a
 * box confirms it, and checks the classes again every {@link #CHECK_MS}: a teacher phone that
 * leaves the Wi-Fi sends no goodbye, and the network keeps its announcement for about 2 minutes.
 * When the network knows a class that no box confirms (the teacher may be back at a new address),
 * the search starts again, at most every {@link #RESEARCH_MS}, so the network gives the new
 * address, up to {@link #MAX_RESEARCHES} times in a row (review N4: a teacher that left keeps its
 * announcement on the network for about 2 minutes). The list is for the screen only, and it is
 * empty again at the next search.
 */
public class FindClassesViewModel extends ViewModel {

    /** After this time with no class found, the tab says that it found none. */
    static final long SEARCH_MS = 5000;
    static final long CHECK_MS = 5000;
    static final long RESEARCH_MS = 10000;
    /** Searches in a row for a class that no box confirms: one minute, then the tab waits. */
    static final int MAX_RESEARCHES = 6;

    private final ClassFinder finder;
    private final ClassProbe probe;
    private final Executor io;
    private final Handler main = new Handler(Looper.getMainLooper());

    // Main thread only. Insertion order: a class stays at its place in the list.
    // key -> (address -> candidate): what the network reported in this search.
    private final Map<String, Map<String, FoundClass>> known = new LinkedHashMap<>();
    // key -> the candidate that its box confirmed.
    private final Map<String, FoundClass> shown = new LinkedHashMap<>();
    private final MutableLiveData<List<FoundClass>> classes = new MutableLiveData<>(Collections.emptyList());
    private final MutableLiveData<Boolean> searching = new MutableLiveData<>(false);
    // A probe answer of an older search changes nothing.
    private int round;
    // key -> number of its newest probe: only the newest answer counts (review N3: an older probe
    // to a dead address can end last). Cleared only at a new search: a key that is lost and found
    // again keeps counting, so an old probe of it cannot match.
    private final Map<String, Integer> probeOf = new HashMap<>();
    private boolean running;
    private long lastSearchAt;
    private int researches;   // searches in a row with a class that no box confirms

    private final Runnable searchEnd = () -> searching.setValue(false);
    private final Runnable check = this::checkAll;
    private final ClassFinder.Listener onFinder = new ClassFinder.Listener() {
        @Override public void onFound(FoundClass c) {
            Map<String, FoundClass> at = known.get(c.key);
            if (at == null) known.put(c.key, at = new LinkedHashMap<>());
            if (!at.containsKey(c.address)) researches = 0;   // a new address: search again if needed
            at.remove(c.address);   // the newest report goes last: it is checked first
            at.put(c.address, c);
            confirm(c.key);
        }
        @Override public void onLost(String key) {
            known.remove(key);
            if (shown.remove(key) != null) publish();
        }
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
        known.clear();
        shown.clear();
        probeOf.clear();
        researches = 0;
        publish();
        searching.setValue(true);
        main.postDelayed(searchEnd, SEARCH_MS);
        main.postDelayed(check, CHECK_MS);
        lastSearchAt = SystemClock.elapsedRealtime();
        finder.start(onFinder);
    }

    public void stop() {
        running = false;
        main.removeCallbacks(searchEnd);
        main.removeCallbacks(check);
        searching.setValue(false);
        finder.stop();
    }

    /** Asks the box at each address of the class, newest first; the first that confirms wins. */
    private void confirm(String key) {
        Map<String, FoundClass> at = known.get(key);
        if (at == null) return;
        List<FoundClass> candidates = new ArrayList<>(at.values());
        Collections.reverse(candidates);
        final int r = round;
        final int mine = probeOf.merge(key, 1, Integer::sum);
        io.execute(() -> {
            FoundClass open = null;
            for (FoundClass c : candidates) {
                if (c.code.equals(probe.codeAt(c.address))) { open = c; break; }
            }
            final FoundClass confirmed = open;
            main.post(() -> {
                if (!running || r != round || !known.containsKey(key)) return;
                if (!Integer.valueOf(mine).equals(probeOf.get(key))) return;   // a newer probe runs
                if (confirmed != null) shown.put(key, confirmed);
                else shown.remove(key);
                publish();
            });
        });
    }

    private void checkAll() {
        if (!running) return;
        for (String key : new ArrayList<>(known.keySet())) confirm(key);
        long now = SystemClock.elapsedRealtime();
        if (shown.size() >= known.size()) {
            researches = 0;
        } else if (now - lastSearchAt >= RESEARCH_MS && researches < MAX_RESEARCHES) {
            researches++;
            lastSearchAt = now;
            finder.stop();   // a new search asks the network again: it gives the current address
            finder.start(onFinder);
        }
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
