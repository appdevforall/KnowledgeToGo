package org.appdevforall.k2go.contentexchange.domain;

/**
 * Pure tri-state rule for a per-app group "select all" control. A group header reports NONE, SOME or
 * ALL from how many of the group's selectable items are selected, and one tap flips the whole group.
 *
 * <p>No Android, no state of its own: the counts are derived from the single selection source (the
 * ViewModel's selected set), so this never becomes a second place that stores "what is selected".
 * Unit-testable on a plain JVM.
 */
public final class GroupSelection {

    public static final int NONE = 0;
    public static final int SOME = 1;
    public static final int ALL = 2;

    private GroupSelection() {}

    /** Tri-state for a group given its selectable member count and how many of them are selected. */
    public static int state(int selectableCount, int selectedCount) {
        if (selectableCount <= 0 || selectedCount <= 0) {
            return NONE;
        }
        if (selectedCount >= selectableCount) {
            return ALL;
        }
        return SOME;
    }

    /** A header tap selects the whole group unless it is already fully selected, then it clears it. */
    public static boolean shouldSelectAll(int state) {
        return state != ALL;
    }
}
