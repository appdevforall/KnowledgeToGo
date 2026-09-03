package org.appdevforall.k2go.deploy.domain;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class ElfClassTest {
    private static byte[] elf(int eiClass) {
        return new byte[]{0x7F, 'E', 'L', 'F', (byte) eiClass, 0, 0, 0};
    }

    @Test public void detects32() { assertEquals(ElfClass.BITS_32, ElfClass.of(elf(1))); }
    @Test public void detects64() { assertEquals(ElfClass.BITS_64, ElfClass.of(elf(2))); }
    @Test public void unknownForNonElf() {
        assertEquals(ElfClass.UNKNOWN, ElfClass.of("#!/bin/sh\n".getBytes()));
        assertEquals(ElfClass.UNKNOWN, ElfClass.of(new byte[]{0x7F, 'E', 'L', 'F'})); // too short
        assertEquals(ElfClass.UNKNOWN, ElfClass.of(null));
        assertEquals(ElfClass.UNKNOWN, ElfClass.of(elf(9))); // bad class byte
    }
}
