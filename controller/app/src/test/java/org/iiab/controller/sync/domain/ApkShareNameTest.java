package org.iiab.controller.sync.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;

/** The download name is brand + version + arch, always file/URL safe. Pure JVM. */
public class ApkShareNameTest {

    @Test
    public void typicalBuild() {
        assertEquals("K2Go-v0.4.1-beta-arm64-v8a.apk",
                ApkShareName.fileName("v0.4.1-beta", "arm64-v8a"));
    }

    @Test
    public void thirtyTwoBitArch() {
        assertEquals("K2Go-v0.4.1-beta-armeabi-v7a.apk",
                ApkShareName.fileName("v0.4.1-beta", "armeabi-v7a"));
    }

    @Test
    public void nullPartsFallBack() {
        assertEquals("K2Go-unknown-unknown.apk", ApkShareName.fileName(null, null));
    }

    @Test
    public void stripsUnsafeCharsAndWhitespace() {
        // spaces, slashes and a CI suffix with odd chars must not break the name/URL
        assertEquals("K2Go-v0.4.1-beta-ci-42-arm64-v8a.apk",
                ApkShareName.fileName("  v0.4.1-beta ci/42 ", "arm64/v8a"));
    }

    @Test
    public void emptyPartsFallBack() {
        assertEquals("K2Go-unknown-x86_64.apk", ApkShareName.fileName("   ", "x86_64"));
    }

    // --- archLabel: describe the FILE, not the device (ADFA-4540) ---

    @Test
    public void archLabelSingleAbi() {
        assertEquals("arm64-v8a",
                ApkShareName.archLabel(Collections.singletonList("arm64-v8a")));
    }

    @Test
    public void archLabelMultipleAbisIsUniversal() {
        // universal build (both ABIs packaged) must NOT masquerade as one arch
        assertEquals("universal",
                ApkShareName.archLabel(new LinkedHashSet<>(Arrays.asList("arm64-v8a", "armeabi-v7a"))));
    }

    @Test
    public void archLabelEmptyIsNoarch() {
        assertEquals("noarch", ApkShareName.archLabel(Collections.emptyList()));
    }

    @Test
    public void archLabelNullIsNoarch() {
        assertEquals("noarch", ApkShareName.archLabel(null));
    }

    @Test
    public void universalFlowsIntoFileName() {
        String arch = ApkShareName.archLabel(Arrays.asList("arm64-v8a", "armeabi-v7a"));
        assertEquals("K2Go-v0.4.1-beta-universal.apk", ApkShareName.fileName("v0.4.1-beta", arch));
    }
}
