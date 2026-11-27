/*
 * ============================================================================
 * Name        : RingViewModel.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School trust: the setup ring in Settings > School (open, approve, join).
 * ============================================================================
 */
package org.appdevforall.k2go.school.trust.presentation;

import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import org.appdevforall.k2go.school.trust.data.NsdRing;
import org.appdevforall.k2go.school.trust.domain.JoinSchool;
import org.appdevforall.k2go.school.trust.domain.RingHost;
import org.appdevforall.k2go.school.trust.domain.SchoolRing;
import org.appdevforall.k2go.school.trust.domain.SchoolTrustRepository;
import org.appdevforall.k2go.school.trust.domain.TrustRecords;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * The box keeps the ring; this model asks it every 2 s while the ring screen shows and keeps only
 * what the screen shows. The school key is open in memory only while a teacher ring runs.
 */
public class RingViewModel extends ViewModel {

    private static final long POLL_MS = 2000;

    /** The ring of this phone, for the screen. */
    public static final class Host {
        public final SchoolRing.Kind kind;
        public final String pin;
        public final SchoolRing.View view;

        Host(SchoolRing.Kind kind, String pin, SchoolRing.View view) {
            this.kind = kind;
            this.pin = pin;
            this.view = view;
        }
    }

    /** A one-time message for the screen. */
    public static final class Event {
        public enum Kind { OPEN_FAILED, WRONG_PASSWORD, JOIN, CLOSED, SELF_TEACHER }

        public final Kind kind;
        public final JoinSchool.Result result;
        public final String closedBy;
        public final String schoolName;
        private boolean taken;

        Event(Kind kind, JoinSchool.Result result, String closedBy, String schoolName) {
            this.kind = kind;
            this.result = result;
            this.closedBy = closedBy;
            this.schoolName = schoolName;
        }

        public boolean take() {
            if (taken) return false;
            taken = true;
            return true;
        }
    }

    private final SchoolTrustRepository repo;
    private final RingHost host;
    private final JoinSchool joiner;
    private final SchoolRing.TeacherKey teacherKey;
    private final NsdRing nsd;
    private final Executor io;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final MutableLiveData<Host> ring = new MutableLiveData<>(null);
    private final MutableLiveData<List<SchoolRing.Found>> found = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> waiting = new MutableLiveData<>(false);
    private final MutableLiveData<Event> event = new MutableLiveData<>();
    // Main thread only: the rings on the network, by mDNS name and address.
    private final Map<String, SchoolRing.Found> rings = new LinkedHashMap<>();
    private final Runnable poll = this::pollRing;
    private JoinSchool.Pending pending;   // main thread only
    private boolean finding;

    public RingViewModel(SchoolTrustRepository repo, SchoolRing.Box box, SchoolRing.TeacherKey teacherKey,
                         NsdRing nsd, Executor io) {
        this.repo = repo;
        this.host = new RingHost(repo, box, teacherKey);
        this.joiner = new JoinSchool(repo, box, teacherKey);
        this.teacherKey = teacherKey;
        this.nsd = nsd;
        this.io = io;
    }

    public LiveData<Host> ring() { return ring; }
    public LiveData<List<SchoolRing.Found>> found() { return found; }
    public LiveData<Boolean> busy() { return busy; }
    public LiveData<Boolean> waiting() { return waiting; }
    public LiveData<Event> event() { return event; }

    /** The public key that this phone sent to the teacher ring that it waits for; null: none. */
    public String waitingKey() {
        JoinSchool.Pending p = pending;
        return p == null ? null : p.key;
    }

    public boolean canEnrollStudents() {
        return host.canEnrollStudents(System.currentTimeMillis() / 1000);
    }

    // ---- the phone that opens the ring ----

    /** Device zero: the password opens the school key, then the teacher ring opens. */
    public void openTeacherRing(String password) {
        if (Boolean.TRUE.equals(busy.getValue())) return;
        busy.setValue(true);
        io.execute(() -> {
            if (!host.unlock(password)) {
                done(new Event(Event.Kind.WRONG_PASSWORD, null, null, null));
                return;
            }
            opened(SchoolRing.Kind.TEACHER, host.open(SchoolRing.Kind.TEACHER, now()));
        });
    }

    public void openStudentRing() {
        if (Boolean.TRUE.equals(busy.getValue())) return;
        busy.setValue(true);
        io.execute(() -> opened(SchoolRing.Kind.STUDENT, host.open(SchoolRing.Kind.STUDENT, now())));
    }

    private void opened(SchoolRing.Kind kind, String pin) {
        if (pin == null) {
            host.lock();
            done(new Event(Event.Kind.OPEN_FAILED, null, null, null));
            return;
        }
        SchoolRing.View v = host.view();
        TrustRecords.Root root = repo.root();
        main.post(() -> {
            busy.setValue(false);
            ring.setValue(new Host(kind, pin, v));
            if (root != null) nsd.announce(kind, root.school, root.name);
            main.removeCallbacks(poll);
            main.postDelayed(poll, POLL_MS);
        });
    }

    private void pollRing() {
        Host h = ring.getValue();
        if (h == null) return;
        io.execute(() -> {
            SchoolRing.View v = host.view();
            main.post(() -> {
                if (ring.getValue() == null) return;   // closed meanwhile
                if (!v.open) {
                    endRing(v.closedBy);
                    return;
                }
                ring.setValue(new Host(h.kind, h.pin, v));
                main.postDelayed(poll, POLL_MS);
            });
        });
    }

    public void approve(SchoolRing.Request r, String label) {
        io.execute(() -> {
            host.approve(r, label, now());
            main.post(() -> { main.removeCallbacks(poll); pollRing(); });
        });
    }

    public void refuse(SchoolRing.Request r) {
        io.execute(() -> {
            host.refuse(r);
            main.post(() -> { main.removeCallbacks(poll); pollRing(); });
        });
    }

    public void closeRing() {
        io.execute(host::close);
        endRing("host");
    }

    private void endRing(String by) {
        main.removeCallbacks(poll);
        nsd.stopAnnounce();
        host.lock();
        if (ring.getValue() != null) {
            ring.setValue(null);
            if (!"host".equals(by)) event.setValue(new Event(Event.Kind.CLOSED, null, by, null));
        }
    }

    /** Device zero is a teacher phone too: the password opens the key for one signature. */
    public void selfTeacher(String password, String label) {
        if (Boolean.TRUE.equals(busy.getValue())) return;
        busy.setValue(true);
        io.execute(() -> {
            if (!host.unlock(password)) {
                done(new Event(Event.Kind.WRONG_PASSWORD, null, null, null));
                return;
            }
            boolean ok = host.selfTeacher(teacherKey, label, now());
            host.lock();
            done(new Event(Event.Kind.SELF_TEACHER, ok ? JoinSchool.Result.OK : JoinSchool.Result.FAILED, null, null));
        });
    }

    // ---- the phone that joins ----

    public void startFind() {
        if (finding) return;
        finding = true;
        rings.clear();
        found.setValue(new ArrayList<>());
        nsd.find(new NsdRing.Listener() {
            @Override public void onFound(SchoolRing.Found r) {
                rings.put(r.service + "@" + r.address, r);
                found.setValue(new ArrayList<>(rings.values()));
            }

            @Override public void onLost(String service) {
                rings.keySet().removeIf(k -> k.startsWith(service + "@"));
                found.setValue(new ArrayList<>(rings.values()));
            }
        });
    }

    public void stopFind() {
        if (!finding) return;
        finding = false;
        nsd.stopFind();
    }

    /** More than one ring of the same school and kind: one can be false. */
    public static boolean twoOfSame(List<SchoolRing.Found> all, SchoolRing.Found r) {
        java.util.Set<String> services = new java.util.HashSet<>();
        for (SchoolRing.Found x : all) {
            if (x.school.equals(r.school) && x.kind == r.kind) services.add(x.service);
        }
        return services.size() > 1;
    }

    public void join(SchoolRing.Found r, String pin) {
        if (Boolean.TRUE.equals(busy.getValue())) return;
        busy.setValue(true);
        io.execute(() -> {
            if (r.kind == SchoolRing.Kind.STUDENT) {
                done(new Event(Event.Kind.JOIN, joiner.student(r, pin), null, r.schoolName));
                return;
            }
            JoinSchool.Step step = joiner.askTeacher(r, pin);
            if (step.result != JoinSchool.Result.WAITING) {
                done(new Event(Event.Kind.JOIN, step.result, null, r.schoolName));
                return;
            }
            main.post(() -> {
                busy.setValue(false);
                pending = step.pending;
                waiting.setValue(true);
                main.postDelayed(this::collect, POLL_MS);
            });
        });
    }

    private void collect() {
        JoinSchool.Pending p = pending;
        if (p == null) return;
        io.execute(() -> {
            JoinSchool.Result res = joiner.collectTeacher(p, now());
            main.post(() -> {
                if (pending != p) return;   // canceled
                if (res == JoinSchool.Result.WAITING) {
                    main.postDelayed(this::collect, POLL_MS);
                    return;
                }
                pending = null;
                waiting.setValue(false);
                event.setValue(new Event(Event.Kind.JOIN, res, null, p.ring.schoolName));
            });
        });
    }

    public void cancelWaiting() {
        pending = null;
        waiting.setValue(false);
    }

    private void done(Event e) {
        main.post(() -> {
            busy.setValue(false);
            event.setValue(e);
        });
    }

    private static long now() { return System.currentTimeMillis() / 1000; }

    @Override
    protected void onCleared() {
        main.removeCallbacksAndMessages(null);
        stopFind();
        if (ring.getValue() != null) {
            nsd.stopAnnounce();
            io.execute(host::close);
        }
        pending = null;
    }
}
