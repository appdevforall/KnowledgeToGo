/*
 * ============================================================================
 * Name        : ZimItemLabel.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5074. The display label for one ZIM item ("Wikipedia · maxi"),
 *               built from the catalogue's project/creator/flavour fields.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import java.util.Locale;

/**
 * How a ZIM item is named in a checklist.
 *
 * <p>Extracted from {@code ZimPreparingFragment} when ADFA-5074 moved the session start out
 * of that screen and into the door. The labels are computed once, at the moment the order is
 * handed to {@code ZimDownloadService}, and the service carries them from then on — so this
 * had to live somewhere that is not the screen that displays them.
 *
 * <p>Pure: no {@code Context}, no resources. The strings it composes come from
 * {@link KiwixCategories}, which is a plain table, so the rules below are unit-testable on a
 * JVM — and they are rules, not formatting: which of creator and flavour is redundant depends
 * on the other two values.
 */
final class ZimItemLabel {

    private ZimItemLabel() {
    }

    /**
     * "Wikipedia · maxi", "Stack Exchange · Ask Ubuntu · nopic", "Wikipedia · All".
     *
     * <p>The redundancy rules, which is the whole reason this is not string concatenation:
     * a creator that merely repeats the project name adds nothing, and neither does the
     * flavour "all" — but dropping both leaves an item with no distinguishing tail at all,
     * so that case says "All" rather than nothing.
     *
     * <p>ADFA-5074 widened that last guard while extracting this. It only fired when the
     * creator repeated the project, so a catalogue entry with <em>no</em> creator and the
     * "all" flavour produced "Wikipedia · " — a label ending on a separator, which reads as a
     * bug. The condition is now "or the tail came out empty", which covers both without
     * changing any label that was already right.
     */
    static String of(String project, String creator, String flavour) {
        KiwixCategories.Category c = KiwixCategories.byKey(project);
        String cat = c != null ? c.title : project;
        if (creator == null) creator = "";
        if (flavour == null || flavour.isEmpty()) flavour = "all";
        boolean creatorIsProject = project != null
                && (creator.equalsIgnoreCase(project)
                || creator.toLowerCase(Locale.ROOT).startsWith(project.toLowerCase(Locale.ROOT)));
        String tail = "all".equals(flavour) ? creator : (creatorIsProject
                ? readable(flavour)
                : creator + " · " + readable(flavour));
        if (("all".equals(flavour) && creatorIsProject) || tail.isEmpty()) tail = "All";
        return cat + " · " + tail;
    }

    /** Catalogue flavours are machine tokens ("no_pic", "max-full"); the user reads words. */
    private static String readable(String flavour) {
        return flavour.replace('_', ' ').replace('-', ' ');
    }
}
