/*
 * ============================================================================
 * Name        : ProotEnvironment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5362. The single builder of the environment every proot
 *               launch runs with.
 * ============================================================================
 */
package org.appdevforall.k2go.proot.data;

import org.appdevforall.k2go.proot.domain.SeccompMode;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One place that answers "what environment does a proot launch run with".
 *
 * <p>The same block was written out four times — twice in {@code PRootEngine} (the container
 * command and the interactive shell) and once as {@code export} lines in the generated
 * {@code iiab} CLI, which two of its own launches share. Four copies of one fact is how the
 * terminal and the app end up disagreeing on the same phone, and it is why a per-device launch
 * decision (ADFA-5362) had no single place to land.
 *
 * <p>Pure: files are only probed for existence, nothing is written and there is no {@code android.*},
 * so this is unit-tested on the JVM.
 */
public final class ProotEnvironment {

    /** The guest PATH; a login shell re-derives it, but proot's own exec needs it set. */
    public static final String GUEST_PATH =
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";

    /** proot reads this before its own {@code execvp}; any value disables seccomp filtering. */
    public static final String NO_SECCOMP = "PROOT_NO_SECCOMP";

    private static final String LOADER = "libproot-loader.so";
    private static final String LOADER_32 = "libproot-loader32.so";

    private ProotEnvironment() {
    }

    /**
     * The environment for a proot launch.
     *
     * <p>Callers apply it over a cleared environment ({@code pb.environment().clear()} then
     * {@code putAll}): Android's own variables are toxic to the guest, so the launch starts from
     * nothing and receives exactly this map.
     *
     * <p>The two loader variables are set only when the loader is actually on disk — the 32-bit
     * loader ships on 64-bit ABIs only, and proot falls back to {@code PREFIX} when they are absent.
     *
     * @param nativeLibDir the app's native library directory (where the proot loaders live).
     * @param prefixPath   canonical path of the fake Termux prefix proot resolves its loader through.
     * @param prootTmpPath canonical path of the host directory proot uses for its own temporaries.
     * @param mode         how proot must run here (ADFA-5362); only DISABLED adds a variable.
     */
    public static Map<String, String> build(File nativeLibDir, String prefixPath,
                                            String prootTmpPath, SeccompMode mode) {
        Map<String, String> env = new LinkedHashMap<>();

        env.put("PREFIX", prefixPath);

        // ADFA-5362: absent on a healthy kernel, so the fast path is byte-identical to what shipped.
        if (mode == SeccompMode.DISABLED) {
            env.put(NO_SECCOMP, "1");
        }

        File loader = new File(nativeLibDir, LOADER);
        if (loader.exists()) {
            env.put("PROOT_LOADER", loader.getAbsolutePath());
        }
        File loader32 = new File(nativeLibDir, LOADER_32);
        if (loader32.exists()) {
            env.put("PROOT_LOADER_32", loader32.getAbsolutePath());
        }

        env.put("PROOT_TMP_DIR", prootTmpPath);
        env.put("TMPDIR", "/tmp");
        env.put("HOME", "/root");
        env.put("PATH", GUEST_PATH);
        env.put("TERM", "xterm-256color");
        env.put("LANG", "C.UTF-8");
        env.put("USER", "root");
        env.put("LOGNAME", "root");

        return env;
    }
}
