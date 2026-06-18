package org.iiab.controller.deviceinfo.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Pure-JVM tests for the device-arch rule. No Android — uses a fake provider.
 */
public class GetDeviceArchUseCaseTest {

    private static final class FakeProvider implements DeviceAbiProvider {
        private final String[] a64;
        private final String[] a32;
        private final String[] all;
        FakeProvider(String[] a64, String[] a32, String[] all) {
            this.a64 = a64;
            this.a32 = a32;
            this.all = all;
        }
        @Override public String[] supported64BitAbis() { return a64; }
        @Override public String[] supported32BitAbis() { return a32; }
        @Override public String[] allSupportedAbis() { return all; }
    }

    private static String run(String[] a64, String[] a32, String[] all) {
        return new GetDeviceArchUseCase(new FakeProvider(a64, a32, all)).execute();
    }

    @Test
    public void reports64BitDeviceEvenWhenAppIs32Bit() {
        // The bug scenario: a 32-bit app on a 64-bit device. The device IS arm64,
        // so the device panel must report arm64-v8a, not the app's 32-bit ABI.
        assertEquals("arm64-v8a", run(
                new String[]{"arm64-v8a"},
                new String[]{"armeabi-v7a", "armeabi"},
                new String[]{"arm64-v8a", "armeabi-v7a", "armeabi"}));
    }

    @Test
    public void reports32BitOnlyDevice() {
        assertEquals("armeabi-v7a", run(
                new String[]{},
                new String[]{"armeabi-v7a", "armeabi"},
                new String[]{"armeabi-v7a", "armeabi"}));
    }

    @Test
    public void fallsBackToGenericList() {
        assertEquals("x86", run(new String[]{}, new String[]{}, new String[]{"x86"}));
    }

    @Test
    public void unknownWhenNothingReported() {
        assertEquals("unknown", run(new String[]{}, new String[]{}, new String[]{}));
        assertEquals("unknown", run(null, null, null));
    }

    @Test
    public void skipsEmptyLeadingEntries() {
        assertEquals("arm64-v8a", run(new String[]{"", "arm64-v8a"}, null, null));
    }
}
