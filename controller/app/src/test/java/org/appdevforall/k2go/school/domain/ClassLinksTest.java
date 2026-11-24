package org.appdevforall.k2go.school.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Pure-JVM tests for the class entry rules. */
public class ClassLinksTest {

    private static final int PORT = 8085;

    @Test public void teacherUrlIsOnTheLocalBox() {
        assertEquals("http://localhost:8085/school/teacher/", ClassLinks.teacherUrl("http://localhost:8085"));
    }

    @Test public void connectOpenCodeGivesTheStudentPage() {
        assertEquals("http://192.168.1.167:8085/school/student.html",
                ClassLinks.studentUrlFromCode("http://192.168.1.167:8085", PORT));
        assertEquals("http://192.168.49.1:8085/school/student.html",
                ClassLinks.studentUrlFromCode("  http://192.168.49.1:8085/home  ", PORT));
        assertEquals("http://box.lan:8085/school/student.html",
                ClassLinks.studentUrlFromCode("http://box.lan:8085/", PORT));
    }

    @Test public void otherCodesAreRefused() {
        assertNull(ClassLinks.studentUrlFromCode(null, PORT));
        assertNull(ClassLinks.studentUrlFromCode("", PORT));
        assertNull(ClassLinks.studentUrlFromCode("WIFI:S:K2Go;T:WPA;P:secret;;", PORT));
        assertNull(ClassLinks.studentUrlFromCode("https://192.168.1.167:8085", PORT));
        assertNull(ClassLinks.studentUrlFromCode("http://192.168.1.167", PORT));        // no port: not the box
        assertNull(ClassLinks.studentUrlFromCode("http://192.168.1.167:8080", PORT));
        assertNull(ClassLinks.studentUrlFromCode("http://evil.example:8085@x.y/", PORT));
        assertNull(ClassLinks.studentUrlFromCode("http://999.1.1.1:8085", PORT));
        assertNull(ClassLinks.studentUrlFromCode("http://1.2.3:8085", PORT));
        assertNull(ClassLinks.studentUrlFromCode("javascript:alert(1)", PORT));
    }
}
