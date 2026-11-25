package org.appdevforall.k2go.school.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Pure-JVM tests for a class found on the network. */
public class FoundClassTest {

    private static final int PORT = 8085;

    @Test public void aBoxOnTheBoxPortIsAClass() {
        FoundClass c = FoundClass.of("K2Go class", "192.168.1.167", 8085, PORT);
        assertEquals("K2Go class", c.key);
        assertEquals("192.168.1.167", c.address);
        assertEquals("http://192.168.1.167:8085/school/student.html", c.studentUrl);
    }

    @Test public void anotherPortOrAddressIsRefused() {
        assertNull(FoundClass.of("K2Go class", "192.168.1.167", 80, PORT));
        assertNull(FoundClass.of("K2Go class", "192.168.1.300", 8085, PORT));
        assertNull(FoundClass.of("K2Go class", "evil.example/x?", 8085, PORT));
        assertNull(FoundClass.of("K2Go class", null, 8085, PORT));
        assertNull(FoundClass.of(null, "192.168.1.167", 8085, PORT));
    }
}
