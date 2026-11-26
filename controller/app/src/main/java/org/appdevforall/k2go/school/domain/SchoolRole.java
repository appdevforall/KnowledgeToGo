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
 * The role that the school gives a phone at school setup (book chapter 08, "Good" level). No role is
 * the default: the phone can teach, join and open the developer terminal, as before roles.
 *
 * <p>The box reads the same file with the same rule (static/dashboard/school/role.ts): keep them equal.
 */
public enum SchoolRole {
    NONE,
    TEACHER,
    STUDENT;

    /**
     * The role for the text of the role file ({@code null}: there is no file). Any text other than
     * "teacher" is a student: a file that the school made always takes rights away.
     */
    public static SchoolRole parse(String text) {
        if (text == null) return NONE;
        return "teacher".equalsIgnoreCase(text.trim()) ? TEACHER : STUDENT;
    }

    /** A student phone cannot open a class. */
    public boolean canTeach() { return this != STUDENT; }

    /** A student phone has no developer terminal. */
    public boolean canUseTerminal() { return this != STUDENT; }
}
