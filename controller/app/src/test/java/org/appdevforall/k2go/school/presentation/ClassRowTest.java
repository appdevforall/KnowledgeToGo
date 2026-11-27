package org.appdevforall.k2go.school.presentation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.appdevforall.k2go.school.domain.FoundClass;
import org.appdevforall.k2go.school.trust.domain.VerifyClass;
import org.junit.Test;

/** The search of "Choose a class": by ID or by name, in any case. */
public class ClassRowTest {

    private static ClassRow row(String code, String name) {
        return new ClassRow(new FoundClass("K2Go class " + code, code, "192.168.1.5",
                "http://192.168.1.5:8085/school/student.html"), name, null, VerifyClass.Result.NO_SCHOOL);
    }

    @Test public void anEmptySearchShowsAll() {
        assertTrue(row("LLGU", null).matches(""));
        assertTrue(row("LLGU", null).matches("   "));
        assertTrue(row("LLGU", null).matches(null));
    }

    @Test public void theIdMatchesInAnyCase() {
        assertTrue(row("LLGU", null).matches("llg"));
        assertTrue(row("LLGU", null).matches(" GU "));
        assertFalse(row("LLGU", null).matches("PG"));
    }

    @Test public void theNameMatchesInAnyCase() {
        assertTrue(row("LLGU", "Math 101").matches("math"));
        assertTrue(row("LLGU", "Math 101").matches("101"));
        assertFalse(row("LLGU", "Math 101").matches("art"));
        assertFalse(row("PGNK", null).matches("math"));   // no name: only the ID counts
    }
}
