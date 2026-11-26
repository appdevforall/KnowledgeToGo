/*
 * ============================================================================
 * Name        : ClassStarter.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School: port to open a class on this phone's box, with its name.
 * ============================================================================
 */
package org.appdevforall.k2go.school.domain;

/**
 * The box is the one owner of the class and of its name: the app gives the name when the class
 * starts and keeps no copy. The box cleans the name (at most 20 code points) and refuses a rename.
 */
public interface ClassStarter {

    /** What the box did. */
    enum Result {
        /** A new class is open, with the name (or with no name). */
        STARTED,
        /** A class was open already: it keeps its code and its name. */
        ALREADY_OPEN,
        /** The box did not answer, or it has no class module. */
        FAILED
    }

    /** BLOCKS: opens a class on this phone's box. {@code name}: null or empty for no name. */
    Result start(String name);
}
