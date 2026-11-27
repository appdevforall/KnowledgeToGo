/*
 * ============================================================================
 * Name        : SchoolRole.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School: the role of a school phone (teacher, student, or none).
 * ============================================================================
 */
package org.appdevforall.k2go.school.domain;

/**
 * The role of a school phone (book chapter 08). It is not stored: PhoneRole derives it from the school
 * trust files each time. No role is the default: the phone can teach and join, as before roles. The
 * terminal stays open on every phone (maintainer, 2026-11-26): a change on a student phone affects only
 * that phone, because the other phones check the signatures of the school.
 */
public enum SchoolRole {
    NONE,
    TEACHER,
    STUDENT;

    /** A student phone cannot open a class. */
    public boolean canTeach() { return this != STUDENT; }
}
