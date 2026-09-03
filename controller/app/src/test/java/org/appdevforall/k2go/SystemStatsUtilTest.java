package org.appdevforall.k2go;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit tests for {@link SystemStatsUtil}. These run on the plain JVM — no emulator.
 * This is the first safety-net test added in Phase 0 of the tech-debt remediation plan.
 */
public class SystemStatsUtilTest {

    // --- parseMemLine ---

    @Test
    public void parseMemLine_parsesStandardMeminfoLine() {
        assertEquals(8127200L, SystemStatsUtil.parseMemLine("MemTotal:        8127200 kB"));
    }

    @Test
    public void parseMemLine_parsesAvailableLine() {
        assertEquals(123456L, SystemStatsUtil.parseMemLine("MemAvailable:   123456 kB"));
    }

    @Test
    public void parseMemLine_handlesLeadingAndTrailingWhitespace() {
        assertEquals(42L, SystemStatsUtil.parseMemLine("   SwapFree:   42 kB   "));
    }

    @Test
    public void parseMemLine_returnsZeroForNull() {
        assertEquals(0L, SystemStatsUtil.parseMemLine(null));
    }

    @Test
    public void parseMemLine_returnsZeroForEmpty() {
        assertEquals(0L, SystemStatsUtil.parseMemLine(""));
    }

    @Test
    public void parseMemLine_returnsZeroWhenSecondTokenNotNumeric() {
        assertEquals(0L, SystemStatsUtil.parseMemLine("MemTotal: notANumber kB"));
    }

    @Test
    public void parseMemLine_returnsZeroWhenNoSecondToken() {
        assertEquals(0L, SystemStatsUtil.parseMemLine("MemTotal:"));
    }

    // --- getDebianArch ---

    @Test
    public void getDebianArch_mapsAarch64ToArm64() {
        assertEquals("arm64", SystemStatsUtil.getDebianArch("aarch64"));
    }

    @Test
    public void getDebianArch_mapsArm64VariantToArm64() {
        assertEquals("arm64", SystemStatsUtil.getDebianArch("ARM64-v8a"));
    }

    @Test
    public void getDebianArch_mapsArmeabiToArmhf() {
        assertEquals("armhf", SystemStatsUtil.getDebianArch("armeabi-v7a"));
    }

    @Test
    public void getDebianArch_mapsArmv7ToArmhf() {
        assertEquals("armhf", SystemStatsUtil.getDebianArch("armv7l"));
    }

    @Test
    public void getDebianArch_returnsNaForNull() {
        assertEquals("N/A", SystemStatsUtil.getDebianArch(null));
    }

    @Test
    public void getDebianArch_returnsNaForNaSentinel() {
        assertEquals("N/A", SystemStatsUtil.getDebianArch("N/A"));
    }

    @Test
    public void getDebianArch_lowercasesUnknownArch() {
        assertEquals("x86_64", SystemStatsUtil.getDebianArch("X86_64"));
    }
}
