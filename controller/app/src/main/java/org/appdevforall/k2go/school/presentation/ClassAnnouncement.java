/*
 * ============================================================================
 * Name        : ClassAnnouncement.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School: announces the class on the Wi-Fi while the box says it is open.
 * ============================================================================
 */
package org.appdevforall.k2go.school.presentation;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import org.appdevforall.k2go.school.data.HttpClassProbe;
import org.appdevforall.k2go.school.data.NsdClassAdvertiser;
import org.appdevforall.k2go.school.domain.Announcement;
import org.appdevforall.k2go.school.domain.ClassAdvertiser;
import org.appdevforall.k2go.school.domain.ClassProbe;
import org.appdevforall.k2go.util.AppExecutors;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Lives with the screen of the teacher page. The box owns "the class is open" (a teacher page is
 * connected to the class channel) and the class code. This observer asks it (every
 * {@link #FIRST_POLL_MS} until the class is on the network, then every {@link #POLL_MS}) and keeps
 * the network announcement in step: a new code replaces the old one. When the screen goes, the announcement goes. If the app process
 * dies, the system removes it.
 */
public final class ClassAnnouncement implements DefaultLifecycleObserver {

    static final long POLL_MS = 3000;
    static final long FIRST_POLL_MS = 1000;
    private static final int MAX_OFF_TRIES = 3;

    private static final String LOCAL_BOX = "localhost";

    private final ClassProbe probe;
    private final ClassAdvertiser advertiser;
    private final Executor io;
    private final Consumer<Boolean> classOpen;   // each answer of the box: is the class open
    private final Handler main = new Handler(Looper.getMainLooper());
    // Main thread only.
    private final Announcement steps = new Announcement();
    private boolean polling;
    private int offTries;   // unregister tries after the screen is gone

    private final Runnable poll = this::poll;
    // The results of a register that ends after the screen is gone still come here, so the
    // announcement that it made is taken off again.
    private final ClassAdvertiser.Callback result = new ClassAdvertiser.Callback() {
        @Override public void done(boolean registered) { act(steps.done(registered)); }
        @Override public void failed() {
            steps.failed();
            // With the screen gone no poll comes: try the unregister again, so the class leaves.
            if (!polling && steps.registered() && offTries++ < MAX_OFF_TRIES) {
                main.postDelayed(() -> act(steps.want(null)), POLL_MS);
            }
        }
    };

    /** {@code classOpen} gets each answer of the box (main thread): true while the class is open. */
    public static ClassAnnouncement create(Context ctx, Consumer<Boolean> classOpen) {
        return new ClassAnnouncement(new HttpClassProbe(), new NsdClassAdvertiser(ctx), AppExecutors.get().io(),
                classOpen);
    }

    ClassAnnouncement(ClassProbe probe, ClassAdvertiser advertiser, Executor io, Consumer<Boolean> classOpen) {
        this.probe = probe;
        this.advertiser = advertiser;
        this.io = io;
        this.classOpen = classOpen;
    }

    @Override
    public void onCreate(@NonNull LifecycleOwner owner) {
        polling = true;
        main.post(poll);
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        polling = false;
        main.removeCallbacks(poll);
        act(steps.want(null));
    }

    private void poll() {
        if (!polling) return;
        io.execute(() -> {
            String code = probe.codeAt(LOCAL_BOX);
            main.post(() -> {
                if (!polling) return;
                classOpen.accept(code != null);
                act(steps.want(code));
                // Ask often until the class opens (the students wait for it), then less often; less
                // often also after failed registers (review L3).
                main.postDelayed(poll, steps.nextPollMs(FIRST_POLL_MS, POLL_MS));
            });
        });
    }

    private void act(Announcement.Action a) {
        if (a == Announcement.Action.REGISTER) advertiser.register(steps.toRegister(), result);
        else if (a == Announcement.Action.UNREGISTER) advertiser.unregister();
    }
}
