package org.appdevforall.k2go.school.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
        assertEquals("http://10.0.0.5:8085/school/student.html",
                ClassLinks.studentUrlFromCode("http://10.0.0.5:8085", PORT));
        assertEquals("http://172.31.255.1:8085/school/student.html",
                ClassLinks.studentUrlFromCode("http://172.31.255.1:8085", PORT));
    }

    @Test public void onlyAPrivateIpv4AddressIsAccepted() {
        assertNull(ClassLinks.studentUrlFromCode("http://box.lan:8085/", PORT));              // a name
        assertNull(ClassLinks.studentUrlFromCode("http://any-site.example:8085", PORT));
        assertNull(ClassLinks.studentUrlFromCode("http://8.8.8.8:8085", PORT));               // public
        assertNull(ClassLinks.studentUrlFromCode("http://172.32.0.1:8085", PORT));            // past 172.16/12
        assertNull(ClassLinks.studentUrlFromCode("http://172.15.0.1:8085", PORT));
        assertNull(ClassLinks.studentUrlFromCode("http://192.169.1.1:8085", PORT));
        assertNull(ClassLinks.studentUrlFromCode("http://127.0.0.1:8085", PORT));             // this phone
        assertNull(ClassLinks.studentUrlFromCode("http://010.0.0.1:8085", PORT));             // octal: 8.0.0.1
    }

    @Test public void teachIsAvailableOnlyWhenTheTeacherPageAnswers200() {
        assertEquals(ClassLinks.Teach.AVAILABLE, ClassLinks.teachFromStatus(200));
        assertEquals(ClassLinks.Teach.NO_MODULE, ClassLinks.teachFromStatus(404));   // no /school/ route
        assertEquals(ClassLinks.Teach.NO_MODULE, ClassLinks.teachFromStatus(302));   // redirect: not the page
        assertEquals(ClassLinks.Teach.NO_MODULE, ClassLinks.teachFromStatus(204));
        assertEquals(ClassLinks.Teach.NO_BOX, ClassLinks.teachFromStatus(502));      // route, dash-node down
        assertEquals(ClassLinks.Teach.NO_BOX, ClassLinks.teachFromStatus(ClassLinks.NO_ANSWER));
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

    @Test public void teacherUrlAndStateUrlAreOnTheLocalBox() {
        assertTrue(ClassLinks.isTeacherUrl("http://localhost:8085/school/teacher/", "http://localhost:8085"));
        assertFalse(ClassLinks.isTeacherUrl("http://localhost:8085/school/student.html", "http://localhost:8085"));
        assertFalse(ClassLinks.isTeacherUrl(null, "http://localhost:8085"));
        assertEquals("http://192.168.1.167:8085/school/state", ClassLinks.stateUrl("192.168.1.167", 8085));
        assertEquals("http://localhost:8085/school/state", ClassLinks.stateUrl("localhost", 8085));
    }

    @Test public void onlyAnOpenClassWithAClassCodeGivesACode() {
        assertEquals("4F7K", ClassLinks.codeFromState("{\"open\":true,\"code\":\"4F7K\"}"));
        assertEquals("4F7K", ClassLinks.codeFromState("{ \"code\" : \"4F7K\", \"open\" : true }"));
        assertNull(ClassLinks.codeFromState("{\"open\":false,\"code\":null}"));
        assertNull(ClassLinks.codeFromState("{\"open\":true,\"code\":null}"));
        assertNull(ClassLinks.codeFromState("{\"open\":true,\"code\":\"4f7k\"}"));
        assertNull(ClassLinks.codeFromState("{\"open\":trueish,\"code\":\"4F7K\"}"));
        assertNull(ClassLinks.codeFromState("<html>404</html>"));
        assertNull(ClassLinks.codeFromState(null));
        assertTrue(ClassLinks.isClassCode("29ZZ"));
        assertFalse(ClassLinks.isClassCode("1I0O"));
        assertFalse(ClassLinks.isClassCode("4F7K5"));
    }

    @Test public void theTokenOfTheOldAddressGoesToTheNewPage() {
        String t = "0123456789abcdef0123456789abcdef";
        assertEquals(t, ClassLinks.tokenFromJs("\"" + t + "\""));
        assertNull(ClassLinks.tokenFromJs("null"));
        assertNull(ClassLinks.tokenFromJs("\"<script>\""));
        assertNull(ClassLinks.tokenFromJs(null));
        // In the fragment: never sent to a server, so in no access log (review B1).
        assertEquals("http://h:8085/school/student.html#t=" + t,
                ClassLinks.withToken("http://h:8085/school/student.html", t));
        assertEquals("http://h:8085/school/student.html#t=" + t,
                ClassLinks.withToken("http://h:8085/school/student.html#t=old", t));
        assertEquals("http://h:8085/school/student.html",
                ClassLinks.withToken("http://h:8085/school/student.html", null));
    }

    @Test public void aClassStateIsAnAnswerOfAClassBox() {
        assertTrue(ClassLinks.isState("{\"open\":false,\"code\":null}"));
        assertTrue(ClassLinks.isState("{\"open\": true, \"code\":\"4F7K\"}"));
        assertFalse(ClassLinks.isState("<html>404</html>"));
        assertFalse(ClassLinks.isState("{\"open\":maybe}"));
        assertFalse(ClassLinks.isState("{\"open\":true,\"code\":\"4f7k\"}"));   // open, but no class code
        assertFalse(ClassLinks.isState(null));
    }

    @Test public void aClassOfTheListGoesWithItsCodeInTheFragment() {
        assertEquals("http://h:8085/school/student.html#c=4F7K",
                ClassLinks.withClass("http://h:8085/school/student.html", "4F7K"));
        assertEquals("http://h:8085/school/student.html#c=4F7K",
                ClassLinks.withClass("http://h:8085/school/student.html#c=OLD1", "4F7K"));
        assertEquals("http://h:8085/school/student.html",
                ClassLinks.withClass("http://h:8085/school/student.html", "4f7k"));      // not a code
    }
}
