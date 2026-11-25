/*
 * ============================================================================
 * Name        : ClassRejoin.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School: the student screen finds its class again after a drop.
 * ============================================================================
 */
package org.appdevforall.k2go.school.presentation;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import org.appdevforall.k2go.school.data.HttpClassProbe;
import org.appdevforall.k2go.school.data.NsdClassFinder;
import org.appdevforall.k2go.school.domain.ClassFinder;
import org.appdevforall.k2go.school.domain.ClassLinks;
import org.appdevforall.k2go.school.domain.ClassProbe;
import org.appdevforall.k2go.school.domain.FoundClass;
import org.appdevforall.k2go.school.domain.Rejoin;
import org.appdevforall.k2go.util.AppExecutors;

import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Lives with the screen of the student page while it is on the screen (onStart to onStop, for the
 * battery). Asks the teacher box for the class code every {@link #PROBE_MS}; the {@link Rejoin}
 * rule decides when to search the Wi-Fi and when the class is at a new address. The move opens the
 * student page there; the page joins the class again by itself.
 */
public final class ClassRejoin implements DefaultLifecycleObserver {

    static final long PROBE_MS = 5000;
    private static final String TAG = "K2GoSchool";

    private final Rejoin rule;
    private final String query;   // the part of the student URL after the path (the name)
    private final ClassProbe probe;
    private final ClassFinder finder;
    private final Executor io;
    private final Consumer<String> open;
    private final Handler main = new Handler(Looper.getMainLooper());
    // Main thread only.
    private boolean running;
    private int round;   // a probe answer of an older start is not used

    private final Runnable tick = this::probeNow;
    private final ClassFinder.Listener onFound = new ClassFinder.Listener() {
        @Override public void onFound(FoundClass c) { act(rule.found(c), c); }
        @Override public void onLost(String key) {}
    };

    // Intent extras of a student page opened from Class > Join (ClassFragment).
    static final String EXTRA_CODE = "K2GO_CLASS_CODE";
    static final String EXTRA_ADDRESS = "K2GO_CLASS_ADDRESS";
    static final String EXTRA_QUERY = "K2GO_CLASS_QUERY";

    /** Adds the student extras to the intent of the portal screen. */
    static void putExtras(android.content.Intent i, FoundClass c, String query) {
        i.putExtra(EXTRA_CODE, c.code);
        i.putExtra(EXTRA_ADDRESS, c.address);
        i.putExtra(EXTRA_QUERY, query);
    }

    // The student page keeps its class token here (school/www/student.html).
    private static final String READ_TOKEN = "localStorage.getItem('k2go-school-token')";

    /**
     * The one call of the portal screen: when it shows a student page opened from Class > Join,
     * the screen finds its class again after a drop. {@code web} gives the current WebView.
     */
    public static void attachIfStudent(androidx.appcompat.app.AppCompatActivity screen,
                                       Supplier<WebView> web) {
        android.content.Intent i = screen.getIntent();
        String code = i.getStringExtra(EXTRA_CODE);
        String address = i.getStringExtra(EXTRA_ADDRESS);
        if (code == null || address == null) return;
        screen.getLifecycle().addObserver(
                create(screen, code, address, i.getStringExtra(EXTRA_QUERY), moveIn(web)));
    }

    /**
     * Opens the student page at the new address with the class token of the old page, so the box
     * keeps one entry for this student (the new address is a new origin, with no stored token).
     * The screen loads it with loadUrl: a navigation that the page starts (location.replace) goes
     * to the portal rule, which sends a host that is not this phone to the browser. When the new
     * page shows, the history is cleared, so Back does not open the old (dead) address.
     */
    private static Consumer<String> moveIn(Supplier<WebView> web) {
        Handler ui = new Handler(Looper.getMainLooper());
        return url -> {
            WebView w = web.get();
            if (w == null) return;
            w.evaluateJavascript(READ_TOKEN, js -> {
                String target = ClassLinks.withToken(url, ClassLinks.tokenFromJs(js));
                WebView now = web.get();
                if (now == null) return;
                now.loadUrl(target);
                clearHistoryWhenShown(ui, web, target, CLEAR_TRIES);
            });
        };
    }

    private static final int CLEAR_TRIES = 20;   // every 500 ms: 10 s
    private static void clearHistoryWhenShown(Handler ui, Supplier<WebView> web, String url, int tries) {
        ui.postDelayed(() -> {
            WebView w = web.get();
            if (w == null) return;
            if (url.equals(w.getUrl()) && w.getProgress() == 100) w.clearHistory();
            else if (tries > 1) clearHistoryWhenShown(ui, web, url, tries - 1);
        }, 500);
    }

    /**
     * @param code    the class code that the student joined
     * @param address the IPv4 address of the teacher box
     * @param query   the query of the student URL (for example "?n=Galaxy%20A16"), or ""
     * @param open    opens a student page URL in the screen (main thread)
     */
    public static ClassRejoin create(Context ctx, String code, String address, String query,
                                     Consumer<String> open) {
        return new ClassRejoin(new Rejoin(code, address), query, new HttpClassProbe(),
                new NsdClassFinder(ctx), AppExecutors.get().io(), open);
    }

    ClassRejoin(Rejoin rule, String query, ClassProbe probe, ClassFinder finder, Executor io,
                Consumer<String> open) {
        this.rule = rule;
        this.query = query == null ? "" : query;
        this.probe = probe;
        this.finder = finder;
        this.io = io;
        this.open = open;
    }

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        running = true;
        round++;
        main.postDelayed(tick, PROBE_MS);
    }

    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        running = false;
        main.removeCallbacks(tick);
        finder.stop();
    }

    private void probeNow() {
        if (!running) return;
        final int r = round;
        final String at = rule.address();
        io.execute(() -> {
            String code = probe.codeAt(at);
            main.post(() -> {
                if (!running || r != round) return;
                if (at.equals(rule.address())) act(rule.probed(code), null);   // else: it moved meanwhile
                main.postDelayed(tick, PROBE_MS);
            });
        });
    }

    private void act(Rejoin.Action a, FoundClass c) {
        switch (a) {
            case SEARCH:
                finder.stop();   // a new search asks the network again (a fresh address)
                finder.start(onFound);
                break;
            case STOP_SEARCH:
                finder.stop();
                break;
            case CHECK:
                final int r = round;
                io.execute(() -> {
                    String there = probe.codeAt(c.address);
                    main.post(() -> { if (running && r == round) act(rule.confirmed(c, there), c); });
                });
                break;
            case MOVE:
                finder.stop();
                Log.i(TAG, "class found again at a new address");
                open.accept(c.studentUrl + query);
                break;
            default:
                break;
        }
    }
}
