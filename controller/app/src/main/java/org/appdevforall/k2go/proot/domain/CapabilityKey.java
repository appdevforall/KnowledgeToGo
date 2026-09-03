/*
 * ============================================================================
 * Name        : CapabilityKey.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5362. When does a remembered proot verdict still apply?
 * ============================================================================
 */
package org.appdevforall.k2go.proot.domain;

/**
 * The rule that keeps a remembered launch verdict from outliving what it was true about.
 *
 * <p>{@link SeccompMode#DISABLED} is learned from one kernel running one proot binary. Three things
 * can make that answer wrong: the OS build changes (a vendor update can fix the kernel), the kernel
 * string changes, or the app ships a different proot. So the verdict is stored against a key made of
 * all three, and only counts while that key still describes the device. That is also why nothing
 * ever has to clear the stored value — a key that no longer matches is already ignored.
 *
 * <p>Getting this wrong is quiet, not loud: a verdict that outlives its key leaves a healthy device
 * permanently slower with no symptom to notice. Hence a pure rule with tests, rather than a string
 * comparison buried in the data layer.
 */
public final class CapabilityKey {

    /** Stands in for a component the platform did not report, so the key stays well-formed. */
    private static final String UNKNOWN = "unknown";

    private static final String SEPARATOR = "|";

    private CapabilityKey() {
    }

    /**
     * Build the key a verdict is stored against.
     *
     * @param osBuild    the OS build identity (Android's {@code Build.FINGERPRINT}).
     * @param kernel     the kernel version string ({@code os.version}).
     * @param appVersion the app's version code — a new APK can carry a different proot.
     */
    public static String of(String osBuild, String kernel, int appVersion) {
        return orUnknown(osBuild) + SEPARATOR + orUnknown(kernel) + SEPARATOR + appVersion;
    }

    /**
     * Does a verdict stored under {@code storedKey} still describe this device?
     *
     * <p>False for anything that is not an exact match, including nothing stored at all: never
     * having learned and having learned about something else both mean "do not use a verdict here".
     */
    public static boolean holds(String storedKey, String currentKey) {
        return storedKey != null && storedKey.equals(currentKey);
    }

    private static String orUnknown(String component) {
        return (component == null || component.isEmpty()) ? UNKNOWN : component;
    }
}
