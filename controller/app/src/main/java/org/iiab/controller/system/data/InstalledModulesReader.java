/*
 * ============================================================================
 * Name        : InstalledModulesReader.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5104. Reads the installer's flags off the rootfs, with the
 *               box up or down. No proot, no network.
 * ============================================================================
 */
package org.iiab.controller.system.data;

import android.content.Context;
import android.util.Log;

import org.iiab.controller.ModuleRegistry;
import org.iiab.controller.SystemStateEvaluator;
import org.iiab.controller.system.domain.InstalledModules;
import org.iiab.controller.system.domain.PlatformPresence;
import org.iiab.controller.util.LocalVarsYamlParser;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * What is installed, read from the rootfs instead of asked over HTTP.
 *
 * <p>{@code etc/iiab/local_vars.yml} is a plain file inside the rootfs directory, so this is an
 * ordinary file read on the host filesystem — no proot is entered and no service needs to be
 * running. That is the whole reason this exists: the screens that ask "what is installed" must
 * keep working with the box stopped, and until now they asked a probe, which cannot.
 *
 * <p><b>Invalidation is free.</b> The file lives inside the rootfs, so a reset, restore, clone or
 * reinstall replaces or removes it along with everything else. There is no cache to clear and no
 * destructive route that can be forgotten — the failure mode that a SharedPreferences copy of the
 * same fact would have had, and does have: {@code installed_tier} is written once and cleared by
 * nobody.
 *
 * <p><b>Not free of I/O.</b> Small file, but a file: call this off the main thread. It is cheap
 * enough to call per screen and too expensive to call per row.
 */
public final class InstalledModulesReader {

    private static final String TAG = "K2Go-Modules";
    private static final String LOCAL_VARS = "etc/iiab/local_vars.yml";

    /** Refuse to read anything absurd for this file; a real one is a few KB. */
    private static final long MAX_BYTES = 512L * 1024L;

    private InstalledModulesReader() {
    }

    /**
     * The installer's flags, or {@code null} when they cannot be read.
     *
     * <p>Null is a distinct answer and callers must keep it distinct: no system, a system being
     * laid down right now, or a rootfs we cannot read. None of those mean "nothing is installed".
     */
    public static JSONObject readFlags(Context ctx) {
        if (ctx == null) {
            return null;
        }
        File file = new File(SystemStateEvaluator.rootfsDir(ctx), LOCAL_VARS);
        try {
            if (!file.isFile() || !file.canRead()) {
                return null;
            }
            if (file.length() > MAX_BYTES) {
                Log.w(TAG, "local_vars.yml is " + file.length() + " bytes; refusing to parse");
                return null;
            }
            return LocalVarsYamlParser.parseToJson(readUtf8(file));
        } catch (Exception e) {
            // Vanished mid-read, permissions, a wipe in flight. All of them are "not established".
            Log.w(TAG, "could not read local_vars.yml", e);
            return null;
        }
    }

    /**
     * The file as text.
     *
     * <p>Plain streams rather than {@code Files.readAllBytes}: {@code java.nio.file} is API 26 and
     * this app ships to 24 with no desugaring, so the tidier call would crash on the oldest
     * devices we support — which are exactly the ones this product exists for.
     */
    private static String readUtf8(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Which presentable modules the flags claim are installed, or {@code null} when the flags
     * could not be read at all.
     *
     * <p>Null rather than an empty set, and the difference is the whole point. "Nothing is
     * installed" and "I could not look" are different answers, and a caller that receives the
     * empty set for both will offer to install what is already there the moment the file is
     * missing, unreadable or half-written — which is the defect this class exists to remove,
     * reappearing one layer down. An earlier version of this method did exactly that.
     */
    public static Set<String> installedKeys(Context ctx) {
        JSONObject flags = readFlags(ctx);
        if (flags == null) {
            return null;
        }
        return InstalledModules.from(flags, ModuleRegistry.validYamlKeys());
    }

    /**
     * Disk evidence for one module, ready to be weighed against a probe.
     *
     * <p>Disk wins where the two disagree, and the reason is in the asymmetry rather than in a
     * preference: a readable file that does not carry the flag is a statement by the installer,
     * while a probe that does not answer is usually a statement about the network. The probe's
     * job here is to correct the one window disk cannot see — the flag is written before the
     * runrole and reverted if it fails, so a process death in between leaves a claim that never
     * came true.
     */
    public static PlatformPresence.Evidence evidenceFor(Context ctx, String yamlBaseKey) {
        return InstalledModules.evidenceFor(readFlags(ctx), yamlBaseKey);
    }
}
