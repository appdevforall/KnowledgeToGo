/*
 * ============================================================================
 * Name        : HelpLink.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : A tier-3 help link (label + relative URI) shown in a tooltip.
 * ============================================================================
 */
package org.iiab.controller.help;

/**
 * A tier-3 link. {@link #uri} is a relative path resolved against the tier-3
 * help server base URL (see ADFA-4594). Kept relative so the serving host/port
 * can change without touching the content database.
 */
public final class HelpLink {

    public final String label;
    public final String uri;

    public HelpLink(String label, String uri) {
        this.label = label;
        this.uri = uri;
    }
}
