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

import org.appdevforall.k2go.school.data.HttpClassState;
import org.appdevforall.k2go.school.data.NsdClassAdvertiser;
import org.appdevforall.k2go.school.domain.Announcement;
import org.appdevforall.k2go.school.domain.ClassAdvertiser;
import org.appdevforall.k2go.school.domain.ClassState;
import org.appdevforall.k2go.util.AppExecutors;

import java.util.concurrent.Executor;

/**
 * Lives with the screen of the teacher page. The box owns "the class is open" (a teacher page is
 * connected to the class channel); this observer asks it (every {@link #FIRST_POLL_MS} until the
 * class is on the network, then every {@link #POLL_MS}) and keeps the
 * network announcement in step. When the screen goes, the announcement goes. If the app process
 * dies, the system removes it.
 */
public final class ClassAnnouncement implements DefaultLifecycleObserver {

    static final long POLL_MS = 3000;
    static final long FIRST_POLL_MS = 1000;
    private static final int MAX_OFF_TRIES = 3;

    private final ClassState state;
    private final ClassAdvertiser advertiser;
    private final Executor io;
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
                main.postDelayed(() -> act(steps.want(false)), POLL_MS);
            }
        }
    };

    public static ClassAnnouncement create(Context ctx) {
        return new ClassAnnouncement(new HttpClassState(), new NsdClassAdvertiser(ctx), AppExecutors.get().io());
    }

    ClassAnnouncement(ClassState state, ClassAdvertiser advertiser, Executor io) {
        this.state = state;
        this.advertiser = advertiser;
        this.io = io;
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
        act(steps.want(false));
    }

    private void poll() {
        if (!polling) return;
        io.execute(() -> {
            boolean open = state.open();
            main.post(() -> {
                if (!polling) return;
                act(steps.want(open));
                // Ask often until the class opens (the students wait for it), then less often.
                main.postDelayed(poll, steps.registered() ? POLL_MS : FIRST_POLL_MS);
            });
        });
    }

    private void act(Announcement.Action a) {
        if (a == Announcement.Action.REGISTER) advertiser.register(result);
        else if (a == Announcement.Action.UNREGISTER) advertiser.unregister();
    }
}
