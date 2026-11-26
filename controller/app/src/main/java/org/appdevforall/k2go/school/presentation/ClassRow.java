/*
 * ============================================================================
 * Name        : ClassRow.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School: one row of the Join list (a class that its box confirmed).
 * ============================================================================
 */
package org.appdevforall.k2go.school.presentation;

import org.appdevforall.k2go.school.domain.ClassAge;
import org.appdevforall.k2go.school.domain.FoundClass;

import java.util.Locale;
import java.util.Objects;

/**
 * What one row shows: the code, the name (null: no name) and the age step (null: the box sent no
 * age). Two rows are equal when they show the same, so the list draws again only on a change.
 */
public final class ClassRow {

    public final FoundClass found;
    public final String name;
    public final ClassAge age;

    ClassRow(FoundClass found, String name, ClassAge age) {
        this.found = found;
        this.name = name;
        this.age = age;
    }

    /** "Choose a class" search: the text (trimmed, any case) is in the ID or in the name. Empty: all. */
    public boolean matches(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return true;
        return found.code.toLowerCase(Locale.ROOT).contains(q)
                || (name != null && name.toLowerCase(Locale.ROOT).contains(q));
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ClassRow)) return false;
        ClassRow r = (ClassRow) o;
        return found.key.equals(r.found.key) && found.code.equals(r.found.code)
                && found.address.equals(r.found.address) && found.studentUrl.equals(r.found.studentUrl)
                && Objects.equals(name, r.name)
                && Objects.equals(age, r.age);
    }

    @Override
    public int hashCode() { return Objects.hash(found.key, found.code, found.address, found.studentUrl, name, age); }
}
