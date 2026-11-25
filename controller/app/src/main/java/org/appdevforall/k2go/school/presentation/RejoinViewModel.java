/*
 * ============================================================================
 * Name        : RejoinViewModel.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School: keeps the rejoin rule of a student screen over a re-creation.
 * ============================================================================
 */
package org.appdevforall.k2go.school.presentation;

import androidx.lifecycle.ViewModel;

import org.appdevforall.k2go.school.domain.Rejoin;

/**
 * The student screen starts again after a theme or a language change. The rule keeps the current
 * class address (after a move) and the end of the class, so a new start does not go back to the
 * old address or search for an ended class (review N2). It lives as long as the screen.
 */
public final class RejoinViewModel extends ViewModel {

    private Rejoin rule;   // main thread only

    /** The rule of this screen: made once from the extras of the first start. */
    Rejoin rule(String code, String address) {
        if (rule == null) rule = new Rejoin(code, address);
        return rule;
    }
}
