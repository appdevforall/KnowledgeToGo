/*
 * ============================================================================
 * Name        : ClassProbe.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School: port to read the open class of a box (its code).
 * ============================================================================
 */
package org.appdevforall.k2go.school.domain;

/** The box is the one owner of "a class is open" and of its code. The app asks; it keeps no copy. */
public interface ClassProbe {

    /**
     * BLOCKS: one GET of the class state on the box at {@code host} (an IPv4 address, or
     * "localhost" for this phone's box). Returns the code of the open class, or null when no class
     * is open or the box does not answer.
     */
    String codeAt(String host);
}
