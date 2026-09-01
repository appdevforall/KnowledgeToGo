package org.appdevforall.k2go.proot.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link CapabilityKey} — ADFA-5362.
 *
 * <p>This is the rule that stops a remembered verdict from outliving what it was true about. Its
 * failure mode is silent — a healthy device left permanently slower, with nothing to notice — so
 * the cases below are mostly about the key <em>changing</em> when it must.
 */
public class CapabilityKeyTest {

    /** The affected device this feature was built against. */
    private static final String OS = "Nokia/TA-1039_00WW/PLE:9/PKQ1.181105.001/00WW_6_19F:user/release-keys";
    private static final String KERNEL = "3.18.120-perf";
    private static final int APP = 600;

    private static String key() {
        return CapabilityKey.of(OS, KERNEL, APP);
    }

    @Test
    public void theSameDeviceAndBuildKeepsTheSameKey() {
        assertEquals(key(), CapabilityKey.of(OS, KERNEL, APP));
        assertTrue(CapabilityKey.holds(key(), key()));
    }

    @Test
    public void anOsUpdateRetiresTheVerdict() {
        // A vendor update can fix the kernel; the old "cannot use seccomp" must not survive it.
        String afterUpdate = CapabilityKey.of(
                "Nokia/TA-1039_00WW/PLE:10/QKQ1.190910.002/00WW_7_01A:user/release-keys", KERNEL, APP);
        assertNotEquals(key(), afterUpdate);
        assertFalse(CapabilityKey.holds(key(), afterUpdate));
    }

    @Test
    public void aKernelChangeRetiresTheVerdict() {
        assertFalse(CapabilityKey.holds(key(), CapabilityKey.of(OS, "4.14.356", APP)));
    }

    @Test
    public void aNewAppVersionRetiresTheVerdict() {
        // A new APK can carry a different proot, so the old answer no longer describes the pairing.
        assertFalse(CapabilityKey.holds(key(), CapabilityKey.of(OS, KERNEL, APP + 1)));
    }

    @Test
    public void nothingStoredIsNotAMatch() {
        assertFalse(CapabilityKey.holds(null, key()));
        assertFalse(CapabilityKey.holds("", key()));
    }

    @Test
    public void missingPlatformValuesStillProduceADistinguishingKey() {
        // os.version is nullable in principle. An absent component must not collapse two different
        // devices onto one key -- that would apply one device's verdict to another OS build.
        String noKernel = CapabilityKey.of(OS, null, APP);
        String noKernelOtherOs = CapabilityKey.of("Other/device:9/BUILD:user/release-keys", null, APP);
        assertNotEquals(noKernel, noKernelOtherOs);
        assertFalse(CapabilityKey.holds(noKernel, noKernelOtherOs));
    }

    @Test
    public void anUnknownComponentDoesNotMatchARealOne() {
        // "learned before the kernel was readable" must not be reused once it is readable.
        assertFalse(CapabilityKey.holds(CapabilityKey.of(OS, null, APP), key()));
    }
}
