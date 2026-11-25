package org.appdevforall.k2go.school.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Pure-JVM tests for a class found on the network. */
public class FoundClassTest {

    private static final int PORT = 8085;

    @Test public void aBoxOnTheBoxPortWithAClassCodeIsAClass() {
        FoundClass c = FoundClass.of("K2Go class 4F7K", "4F7K", "192.168.1.167", 8085, PORT);
        assertEquals("K2Go class 4F7K", c.key);
        assertEquals("4F7K", c.code);
        assertEquals("192.168.1.167", c.address);
        assertEquals("http://192.168.1.167:8085/school/student.html", c.studentUrl);
    }

    @Test public void anotherPortAddressOrCodeIsRefused() {
        assertNull(FoundClass.of("K2Go class", "4F7K", "192.168.1.167", 80, PORT));
        assertNull(FoundClass.of("K2Go class", "4F7K", "192.168.1.300", 8085, PORT));
        assertNull(FoundClass.of("K2Go class", "4F7K", "evil.example/x?", 8085, PORT));
        assertNull(FoundClass.of("K2Go class", "4F7K", null, 8085, PORT));
        assertNull(FoundClass.of(null, "4F7K", "192.168.1.167", 8085, PORT));
        assertNull(FoundClass.of("K2Go class", null, "192.168.1.167", 8085, PORT));     // no code
        assertNull(FoundClass.of("K2Go class", "4F7O", "192.168.1.167", 8085, PORT));   // O: not a code character
        assertNull(FoundClass.of("K2Go class", "Ana B", "192.168.1.167", 8085, PORT));
    }
}
