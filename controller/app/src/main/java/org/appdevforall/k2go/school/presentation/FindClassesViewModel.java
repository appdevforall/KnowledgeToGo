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

import org.appdevforall.k2go.school.domain.ClassAge;
import org.appdevforall.k2go.school.domain.ClassFinder;
import org.appdevforall.k2go.school.domain.ClassLinks;
import org.appdevforall.k2go.school.domain.ClassProbe;
import org.appdevforall.k2go.school.domain.FoundClass;
import org.appdevforall.k2go.school.trust.domain.VerifyClass;

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
 * empty again at the next search. Each check also reads the name and the age of the class from
 * its box: the list shows the newest class first, and its age steps on while the tab shows. On a
 * phone of a school, each check also asks the box for the chain of the class (VerifyClass): the box
 * of the class is the one owner of its vouch, so the app keeps no copy.
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
    private final Verifier verifier;
    private final Executor io;
    private final Handler main = new Handler(Looper.getMainLooper());

    // Main thread only. Insertion order: a class stays at its place in the list.
    // key -> (address -> candidate): what the network reported in this search.
    private final Map<String, Map<String, FoundClass>> known = new LinkedHashMap<>();
    // key -> the candidate that its box confirmed, with the last answer of the box.
    private final Map<String, Confirmed> shown = new LinkedHashMap<>();
    private final MutableLiveData<List<ClassRow>> classes = new MutableLiveData<>(Collections.emptyList());
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

    /** BLOCKS: the school check of a class that its box confirmed. */
    public interface Verifier {
        VerifyClass.Result check(FoundClass c);
    }

    public FindClassesViewModel(ClassFinder finder, ClassProbe probe, Verifier verifier, Executor io) {
        this.finder = finder;
        this.probe = probe;
        this.verifier = verifier;
        this.io = io;
    }

    /** The open classes on the Wi-Fi, newest first. */
    public LiveData<List<ClassRow>> classes() { return classes; }

    /** True during the first {@link #SEARCH_MS} of a search. */
    public LiveData<Boolean> searching() { return searching; }

    /**
     * A class code that the student scanned ({@code studentUrl}, from ClassLinks.studentUrlFromCode):
     * the same school check as a class in the list, so a QR on the board cannot open a false class.
     * {@code then} gets true (main thread) when the phone may open the page: a phone with no school,
     * or a verified class. A phone of a school does not open a box with no class open (a false box
     * can say that too).
     */
    public void checkScanned(String studentUrl, java.util.function.Consumer<Boolean> then) {
        io.execute(() -> {
            boolean ok = false;
            try {
                String host = ClassLinks.hostOf(studentUrl);
                String code = host == null ? null : probe.codeAt(host);
                ok = verifier.check(new FoundClass("scan", code, host, studentUrl)).canJoin();
            } finally {
                boolean result = ok;
                main.post(() -> then.accept(result));
            }
        });
    }

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
            Confirmed open = null;
            for (FoundClass c : candidates) {
                ClassProbe.Answer a = probe.ask(c.address);
                if (a != null && c.code.equals(a.code)) {
                    open = new Confirmed(c, a.name, a.ageSec, SystemClock.elapsedRealtime(), verifier.check(c));
                    break;
                }
            }
            final Confirmed confirmed = open;
            main.post(() -> {
                if (!running || r != round || !known.containsKey(key)) return;
                if (!Integer.valueOf(mine).equals(probeOf.get(key))) return;   // a newer probe runs
                if (confirmed != null) shown.put(key, confirmed.sortedAs(shown.get(key)).trustedAs(shown.get(key)));
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

    /** Sets the rows when what they show changed: the age step moves on about once a minute. */
    private void publish() {
        long now = SystemClock.elapsedRealtime();
        List<Confirmed> newest = new ArrayList<>(shown.values());
        // The box age is the one source; a class with no age goes last.
        Collections.sort(newest, (a, b) -> Long.compare(b.order, a.order));
        List<ClassRow> rows = new ArrayList<>();
        for (Confirmed c : newest) rows.add(new ClassRow(c.found, c.name, c.ageAt(now), c.trust));
        if (!rows.equals(classes.getValue())) classes.setValue(Collections.unmodifiableList(rows));
    }

    /** A class that its box confirmed, and the box answer (name, age) at {@code at}. */
    private static final class Confirmed {
        final FoundClass found;
        final String name;
        final long ageSec;   // -1: the box sent no age
        final long at;       // elapsedRealtime() of the answer: this phone's clock, never the time of day
        final VerifyClass.Result trust;

        // The place in the list: the start of the class from the first answer of its box. Each
        // answer gives a start that differs by up to one second (whole seconds, the request time):
        // two classes that started together must not change places at each check.
        private long order;

        Confirmed(FoundClass found, String name, long ageSec, long at, VerifyClass.Result trust) {
            this.found = found;
            this.name = name;
            this.ageSec = ageSec;
            this.at = at;
            this.trust = trust;
            this.order = ageSec < 0 ? Long.MIN_VALUE : at - ageSec * 1000;
        }

        /**
         * A class that was verified stays verified when its box does not answer one hello (a Wi-Fi
         * drop), while its box still confirms the same code at the same address. Any answer of the
         * box decides again.
         */
        Confirmed trustedAs(Confirmed before) {
            if (trust.kind == VerifyClass.Kind.NO_ANSWER && before != null && before.trust.kind == VerifyClass.Kind.VERIFIED
                    && before.found.code.equals(found.code) && before.found.address.equals(found.address)) {
                return new Confirmed(found, name, ageSec, at, before.trust).withOrder(order);
            }
            return this;
        }

        private Confirmed withOrder(long o) {
            order = o;
            return this;
        }

        /** This answer, at the place in the list of {@code before} when it is the same class. */
        Confirmed sortedAs(Confirmed before) {
            if (before != null && before.found.code.equals(found.code) && before.ageSec >= 0 && ageSec >= 0) {
                order = before.order;
            }
            return this;
        }

        ClassAge ageAt(long now) { return ageSec < 0 ? null : ClassAge.of(ageSec + Math.max(0, now - at) / 1000); }
    }

    @Override
    protected void onCleared() {
        stop();
    }
}
