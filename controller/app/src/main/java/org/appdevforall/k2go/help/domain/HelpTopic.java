/*
 * ============================================================================
 * Name        : HelpTopic.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-410. The content axis of the in-app manual: which bundled page to open.
 * ============================================================================
 */
package org.appdevforall.k2go.help.domain;

/**
 * The content axis of the bundled help manual (K2GO-410): which topic page the in-app viewer opens.
 * Each constant names a page file under {@code assets/help/}. Pair it with {@link HelpEntry} to get
 * the resolved path. This is the app-local (assets) manual, separate from the box-served tier-3
 * route in {@link Tier3DocsUrl}.
 */
public enum HelpTopic {

    /** The manual landing page ("Welcome and contents"); the default entry. */
    HOME("index.html"),

    /** The "Install the app" page: the wizard help target while the user is installing. */
    INSTALL("app-install.html");

    private final String fileName;

    HelpTopic(String fileName) {
        this.fileName = fileName;
    }

    /** The page file name within {@code assets/help/}. */
    public String fileName() {
        return fileName;
    }

    /** Resolve a topic by its {@link #name()}; returns {@code fallback} for null or an unknown name. */
    public static HelpTopic fromName(String name, HelpTopic fallback) {
        if (name != null) {
            for (HelpTopic t : values()) {
                if (t.name().equals(name)) {
                    return t;
                }
            }
        }
        return fallback;
    }
}
