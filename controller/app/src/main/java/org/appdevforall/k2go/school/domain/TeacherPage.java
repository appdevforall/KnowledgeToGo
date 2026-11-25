/*
 * ============================================================================
 * Name        : TeacherPage.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School: port to read whether this phone's box serves the teacher page.
 * ============================================================================
 */
package org.appdevforall.k2go.school.domain;

/**
 * The box is the one source of "this box has the class module": it serves the teacher page or it
 * does not. The app asks each time and keeps no copy.
 */
public interface TeacherPage {

    /**
     * BLOCKS: one GET of the teacher page on this phone's box, without redirects. Returns the HTTP
     * status, or {@link ClassLinks#NO_ANSWER} when the box does not answer.
     */
    int status();
}
