/*
 * ============================================================================
 * Name        : Rejoin.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School: a student finds its class again after a drop.
 * ============================================================================
 */
package org.appdevforall.k2go.school.domain;

/**
 * Pure rule. The student page follows the class at one box address and comes back by itself when
 * the Wi-Fi or the box comes back at that address. This rule covers the other case: the teacher
 * box is at a new address (a new IP after a Wi-Fi change). The app asks the box at the current
 * address for its class code; when the class is not there for {@link #MISSES_TO_SEARCH} probes in
 * a row, it searches the network for the same code, and a class with that code at another address
 * is the same class: the student moves there. Main thread only.
 */
public final class Rejoin {

    public enum Action {
        NONE,
        /** Start a search, or start it again: the network may still give an old address. */
        SEARCH,
        STOP_SEARCH,
        /** Open the student page at {@link #address()}. */
        MOVE
    }

    /** Probes with no class at the current address before the search starts. */
    public static final int MISSES_TO_SEARCH = 2;
    /** While it searches, the search starts again after this many more probes. */
    public static final int RESEARCH_EVERY = 2;
    /** Probes with no class after which the search stops (the class ended). */
    public static final int GIVE_UP_MISSES = 60;

    private final String code;
    private String address;
    private int misses;
    private boolean searching;

    public Rejoin(String code, String address) {
        this.code = code;
        this.address = address;
    }

    public String address() { return address; }

    public boolean searching() { return searching; }

    /** One probe of the box at the current address: the code of its open class, or null. */
    public Action probed(String codeThere) {
        if (code.equals(codeThere)) {
            misses = 0;
            return stopSearch();
        }
        misses += 1;
        if (misses >= GIVE_UP_MISSES) return stopSearch();
        if (misses < MISSES_TO_SEARCH) return Action.NONE;
        if (!searching || (misses - MISSES_TO_SEARCH) % RESEARCH_EVERY == 0) {
            searching = true;
            return Action.SEARCH;
        }
        return Action.NONE;
    }

    /** The search found a class. The same code at another address: move there. */
    public Action found(FoundClass c) {
        if (!searching || c == null || !code.equals(c.code) || c.address.equals(address)) return Action.NONE;
        address = c.address;
        misses = 0;
        searching = false;
        return Action.MOVE;
    }

    private Action stopSearch() {
        if (!searching) return Action.NONE;
        searching = false;
        return Action.STOP_SEARCH;
    }
}
