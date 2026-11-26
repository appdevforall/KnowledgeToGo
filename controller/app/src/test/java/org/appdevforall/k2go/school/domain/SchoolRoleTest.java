package org.appdevforall.k2go.school.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The role rule. static/dashboard/school/role.test.ts checks the same cases on the box. */
public class SchoolRoleTest {

    @Test public void noFileIsNoRole() {
        assertEquals(SchoolRole.NONE, SchoolRole.parse(null));
    }

    @Test public void teacherWord() {
        assertEquals(SchoolRole.TEACHER, SchoolRole.parse("teacher"));
        assertEquals(SchoolRole.TEACHER, SchoolRole.parse(" Teacher\n"));
    }

    @Test public void studentWord() {
        assertEquals(SchoolRole.STUDENT, SchoolRole.parse("student\n"));
    }

    @Test public void anyOtherTextIsStudent() {
        assertEquals(SchoolRole.STUDENT, SchoolRole.parse(""));
        assertEquals(SchoolRole.STUDENT, SchoolRole.parse("none"));
        assertEquals(SchoolRole.STUDENT, SchoolRole.parse("teacherx"));
    }

    @Test public void onlyAStudentCannotTeach() {
        assertTrue(SchoolRole.NONE.canTeach());
        assertTrue(SchoolRole.TEACHER.canTeach());
        assertFalse(SchoolRole.STUDENT.canTeach());
    }
}
