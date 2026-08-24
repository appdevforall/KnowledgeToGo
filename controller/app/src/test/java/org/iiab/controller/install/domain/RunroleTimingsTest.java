package org.iiab.controller.install.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.Map;

import org.junit.Test;

/** Unit tests for {@link RunroleTimings} — per-task duration measurement (ADFA-5228). */
public class RunroleTimingsTest {

    @Test
    public void closesEachTaskWhenTheNextStarts() {
        RunroleTimings t = new RunroleTimings();
        t.onTask("a", 0L);
        t.onTask("b", 1000L);
        t.onTask("c", 3000L);
        Map<String, Long> d = t.durationsMs();
        assertEquals(Long.valueOf(1000L), d.get("a"));
        assertEquals(Long.valueOf(2000L), d.get("b"));
        assertFalse("c is still open until finish()", d.containsKey("c"));
    }

    @Test
    public void finishClosesTheLastTask() {
        RunroleTimings t = new RunroleTimings();
        t.onTask("only", 0L);
        t.finish(5000L);
        assertEquals(Long.valueOf(5000L), t.durationsMs().get("only"));
    }

    @Test
    public void repeatedNameKeepsLastMeasurement() {
        RunroleTimings t = new RunroleTimings();
        t.onTask("a", 0L);
        t.onTask("a", 1000L);   // closes first a=1000, reopens a
        t.finish(1500L);        // a=500
        assertEquals(Long.valueOf(500L), t.durationsMs().get("a"));
    }

    @Test
    public void nullTaskIsIgnored() {
        RunroleTimings t = new RunroleTimings();
        t.onTask(null, 0L);
        t.finish(1000L);
        assertEquals(0, t.durationsMs().size());
    }
}
