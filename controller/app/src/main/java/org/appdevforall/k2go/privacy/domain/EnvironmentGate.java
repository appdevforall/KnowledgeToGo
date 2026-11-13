/*
 * ============================================================================
 * Name        : EnvironmentGate.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-559. Domain port: does an exclusive box operation run now?
 * ============================================================================
 */
package org.appdevforall.k2go.privacy.domain;

/** The answer comes from the server lifecycle owner (ADR-5343); this port only asks it. */
public interface EnvironmentGate {

    /** True while a destructive operation holds the box (it stops it to replace or write the system:
     *  install, restore, clone, backup). Downloads, sharing and other live work do not count. */
    boolean isBusy();
}
