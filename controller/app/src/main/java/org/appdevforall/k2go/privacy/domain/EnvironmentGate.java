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

    /** True while an install, restore, clone, backup, download or dashboard update holds the box. */
    boolean isBusy();
}
