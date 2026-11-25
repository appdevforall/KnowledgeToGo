/*
 * ============================================================================
 * Name        : ClassState.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School: port to read "the class is open" from this phone's box.
 * ============================================================================
 */
package org.appdevforall.k2go.school.domain;

/** The box is the one owner of "the class is open". The app asks; it keeps no copy. */
public interface ClassState {

    /** BLOCKS: one GET of the class state on this phone's box. False when the box does not answer. */
    boolean open();
}
