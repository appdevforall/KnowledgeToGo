/*
 * ============================================================================
 * Name        : ClassScreen.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School: the screen stays on while it shows a class page of an open class.
 * ============================================================================
 */
package org.appdevforall.k2go.school.presentation;

import android.view.Window;
import android.view.WindowManager;

import org.appdevforall.k2go.school.domain.ClassLinks;

/**
 * Holds the two inputs of {@link ClassLinks#keepScreenOn}: the page of the screen (the portal tells
 * it at each page start) and the state of the class (the class observers of the screen tell it at
 * each answer of the box). It keeps only the last answer, to put it with the page; the box owns
 * the state of the class. The flag belongs to the window: when the screen goes, the flag goes with
 * it. Main thread only.
 */
public final class ClassScreen {

    private final Window window;
    private String url;
    private boolean open;

    public ClassScreen(Window window) { this.window = window; }

    public void pageStarted(String url) {
        this.url = url;
        apply();
    }

    public void classOpen(boolean open) {
        this.open = open;
        apply();
    }

    private void apply() {
        if (ClassLinks.keepScreenOn(url, open)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
}
