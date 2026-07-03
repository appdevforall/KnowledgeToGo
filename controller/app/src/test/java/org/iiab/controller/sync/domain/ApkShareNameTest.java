package org.iiab.controller.sync.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

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
}
