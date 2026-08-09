package org.iiab.controller.system.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * Unit tests for {@link ContentType} — the list that five screens used to keep by
 * hand, and the one distinction that made those copies dangerous. Pure JVM.
 */
public class ContentTypeTest {

    @Test
    public void mapsIsTheOnlyOneThatRunsWithTheBoxStopped() {
        // The whole reason the class is carried instead of assumed: a caller asking
        // "is a download stream in play?" must not count Maps, and a caller asking
        // "did the user choose any content?" must.
        for (ContentType t : ContentType.values()) {
            assertEquals("wrong class for " + t, t != ContentType.MAPS, t.isLive());
        }
        assertEquals(Operation.ExecutionClass.STOPPED, ContentType.MAPS.executionClass());
    }

    @Test
    public void keysAreUniqueAndRoundTrip() {
        Set<String> seen = new HashSet<>();
        for (ContentType t : ContentType.values()) {
            assertTrue("duplicate key " + t.key(), seen.add(t.key()));
            assertSame(t, ContentType.byKey(t.key()));
        }
    }

    @Test
    public void anUnknownKeyMatchesNothingRatherThanGuessing() {
        // Callers exclude "the stream I just started" by name. If an unrecognised
        // name silently matched something, a screen would hide the wrong row.
        assertNull(ContentType.byKey(null));
        assertNull(ContentType.byKey(""));
        assertNull(ContentType.byKey("Zim"));       // case matters
        assertNull(ContentType.byKey("courses"));   // the key is the platform, not the label
    }

    @Test
    public void theKeyIsThePlatformOnTheOperation() {
        // The progress rows, the detail hints and the dispatcher all have to agree on
        // one name per type; this is where that name is decided.
        for (ContentType t : ContentType.values()) {
            Operation op = t.operation();
            assertNotNull(op);
            assertEquals(t.key(), op.platform());
            assertEquals(Operation.Kind.CONTENT, op.kind());
            assertEquals(t.executionClass(), op.executionClass());
        }
        assertEquals("kolibri", ContentType.COURSES.key());
    }

    @Test
    public void aContentTypeSaysNothingAboutInstallingItsApp() {
        // Courses content is LIVE; the Courses APP is a proot module. Reading one
        // from the other is the conflation ADR-5061 exists to stop.
        assertTrue(ContentType.COURSES.isLive());
        assertFalse(Operation.appInstall(ContentType.COURSES.key()).isLive());
    }
}
