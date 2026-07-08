/*
 * ============================================================================
 * Name        : TooltipItem.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Immutable value object for one tooltip: tier-1 summary,
 *               tier-2 detail, and tier-3 links.
 * ============================================================================
 */
package org.iiab.controller.help;

import java.util.ArrayList;
import java.util.List;

public final class TooltipItem {

    public final String category;
    public final String tag;
    /** Tier 1: short summary (HTML). */
    public final String summary;
    /** Tier 2: longer detail (HTML); may be empty. */
    public final String detail;
    /** Tier 3: links to full help pages; may be empty. */
    public final List<HelpLink> links;

    public TooltipItem(String category, String tag, String summary, String detail,
                       List<HelpLink> links) {
        this.category = category;
        this.tag = tag;
        this.summary = summary;
        this.detail = detail;
        this.links = (links != null) ? links : new ArrayList<HelpLink>();
    }

    public boolean hasDetail() {
        return detail != null && !detail.trim().isEmpty();
    }

    public boolean hasLinks() {
        return links != null && !links.isEmpty();
    }
}
