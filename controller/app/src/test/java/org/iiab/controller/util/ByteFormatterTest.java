package org.iiab.controller.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure-JVM unit tests for {@link ByteFormatter}. */
public class ByteFormatterTest {

    private static final long KIB = 1024L;
    private static final long MIB = KIB * 1024L;
    private static final long GIB = MIB * 1024L;

    @Test
    public void toGiBConvertsExactly() {
        assertEquals(1.0, ByteFormatter.toGiB(GIB), 1e-9);
        assertEquals(1.331, ByteFormatter.toGiB(1_428_970_336L), 1e-3);
    }

    @Test
    public void humanReadableUnits() {
        assertEquals("0 B", ByteFormatter.toHuman(0));
        assertEquals("0 B", ByteFormatter.toHuman(-5));
        assertEquals("512 B", ByteFormatter.toHuman(512));
        assertEquals("1 KiB", ByteFormatter.toHuman(KIB));
        assertEquals("1 MiB", ByteFormatter.toHuman(MIB));
        assertEquals("1.0 GiB", ByteFormatter.toHuman(GIB));
    }

    @Test
    public void rootfsBasicReadsAsExpected() {
        // 1,219,422,532 bytes -> ~1.14 GiB
        String s = ByteFormatter.toHuman(1_219_422_532L);
        assertTrue(s, s.equals("1.1 GiB"));
    }
}
