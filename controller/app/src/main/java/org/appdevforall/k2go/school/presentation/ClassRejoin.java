/*
 * ============================================================================
 * Name        : ClassRejoin.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School: the student screen finds its class again after a drop.
 * ============================================================================
 */
package org.appdevforall.k2go.school.presentation;

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

    /** Adds the student extras to the intent of the portal screen. */
    static void putExtras(android.content.Intent i, FoundClass c) {
        i.putExtra(EXTRA_CODE, c.code);
        i.putExtra(EXTRA_ADDRESS, c.address);
    }

    // The student page keeps its class token here (school/www/student.html).
    private static final String READ_TOKEN = "localStorage.getItem('k2go-school-token')";

    /**
     * The one call of the portal screen: when it shows a student page opened from Class > Join,
     * the screen finds its class again after a drop. {@code web} gives the current WebView;
     * {@code moved} gets the student URL at the new address, so a new start of the screen loads
     * it (review N2). Returns the observer (the portal asks it which hosts belong to the class),
     * or null.
     */
    public static ClassRejoin attachIfStudent(androidx.appcompat.app.AppCompatActivity screen,
                                              Supplier<WebView> web, Consumer<String> moved) {
        android.content.Intent i = screen.getIntent();
        String code = i.getStringExtra(EXTRA_CODE);
        String address = i.getStringExtra(EXTRA_ADDRESS);
        if (code == null || address == null || !ClassLinks.isClassCode(code)) return null;
        Rejoin rule = new androidx.lifecycle.ViewModelProvider(screen).get(RejoinViewModel.class)
                .rule(code, address);
        Consumer<String> open = moveIn(web).andThen(moved);
        ClassRejoin r = new ClassRejoin(rule, new HttpClassProbe(), new NsdClassFinder(screen),
                AppExecutors.get().io(), open);
        screen.getLifecycle().addObserver(r);
        return r;
    }

    /**
     * Opens the student page at the new address with the class token of the old page, so the box
     * keeps one entry for this student (the new address is a new origin, with no stored token).
     * location.replace puts the new page in place of the old one: Back does not open the old
     * (dead) address. The portal keeps it in the app because the box at that address confirmed
     * the class ({@link #isClassHost}). When the old page cannot run it (an error page), the
     * screen loads the URL.
     */
    private static Consumer<String> moveIn(Supplier<WebView> web) {
        return url -> {
            WebView w = web.get();
            if (w == null) return;
            w.evaluateJavascript(READ_TOKEN, js -> {
                String target = ClassLinks.withToken(url, ClassLinks.tokenFromJs(js));
                WebView now = web.get();
                if (now == null) return;
                now.evaluateJavascript(replaceWith(target), done -> {
                    WebView last = web.get();
                    if (!"true".equals(done) && last != null) last.loadUrl(target);
                });
            });
        };
    }

    /** JS that replaces the page with {@code url}: a checked box address and a hex token in the
     *  fragment. The quote escape is a second guard. */
    private static String replaceWith(String url) {
        String q = "'" + url.replace("\\", "\\\\").replace("'", "\\'") + "'";
        return "(function(){try{location.replace(" + q + ");return true;}catch(e){return false;}})()";
    }

    /** True for an address where the box confirmed the class of this screen (see {@link Rejoin}). */
    public boolean isClassHost(String host) { return rule.isClassHost(host); }

    ClassRejoin(Rejoin rule, ClassProbe probe, ClassFinder finder, Executor io, Consumer<String> open) {
        this.rule = rule;
        this.probe = probe;
        this.finder = finder;
        this.io = io;
        this.open = open;
    }

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        running = true;
        round++;
        if (!rule.ended()) main.postDelayed(tick, PROBE_MS);
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
            ClassProbe.Answer answer = probe.ask(at);
            main.post(() -> {
                if (!running || r != round) return;
                if (at.equals(rule.address())) {   // else: it moved meanwhile
                    act(rule.probed(answer == null ? null : answer.code, answer != null), null);
                }
                if (!rule.ended()) main.postDelayed(tick, PROBE_MS);
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
                open.accept(c.studentUrl);
                break;
            case END:
                finder.stop();   // the class ended: no search for its code, no more probes
                Log.i(TAG, "class ended: the student screen stops the search");
                break;
            default:
                break;
        }
    }
}
