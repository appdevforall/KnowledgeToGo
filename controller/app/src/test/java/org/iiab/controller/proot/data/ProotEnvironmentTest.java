package org.iiab.controller.proot.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link ProotEnvironment} — ADFA-5362.
 *
 * <p>These pin the environment map <b>value by value</b>. The builder replaced four hand-written
 * copies of the same block, and a proot launched with a variable missing (or renamed) fails on
 * every device, healthy ones included — so the point of this test is to make any drift from the
 * environment the app shipped with a test failure rather than a field report.
 */
public class ProotEnvironmentTest {

    private File nativeDir;

    @Before
    public void setUp() throws IOException {
        nativeDir = Files.createTempDirectory("nativeLibDir").toFile();
        nativeDir.deleteOnExit();
    }

    private void writeLoader(String name) throws IOException {
        File f = new File(nativeDir, name);
        assertTrue("could not create " + name, f.createNewFile());
        f.deleteOnExit();
    }

    /** The exact map the app launched proot with before the builder existed. */
    private Map<String, String> expectedBase() {
        Map<String, String> expected = new HashMap<>();
        expected.put("PREFIX", "/data/app/files/usr");
        expected.put("PROOT_TMP_DIR", "/data/app/files/proot_tmp");
        expected.put("TMPDIR", "/tmp");
        expected.put("HOME", "/root");
        expected.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        expected.put("TERM", "xterm-256color");
        expected.put("LANG", "C.UTF-8");
        expected.put("USER", "root");
        expected.put("LOGNAME", "root");
        return expected;
    }

    private Map<String, String> build() {
        return ProotEnvironment.build(nativeDir, "/data/app/files/usr", "/data/app/files/proot_tmp");
    }

    @Test
    public void bothLoadersPresentYieldsTheFullShippedEnvironment() throws IOException {
        writeLoader("libproot-loader.so");
        writeLoader("libproot-loader32.so");

        Map<String, String> expected = expectedBase();
        expected.put("PROOT_LOADER", new File(nativeDir, "libproot-loader.so").getAbsolutePath());
        expected.put("PROOT_LOADER_32", new File(nativeDir, "libproot-loader32.so").getAbsolutePath());

        assertEquals(expected, build());
    }

    @Test
    public void thirtyTwoBitLoaderIsOmittedWhenTheAbiDoesNotShipIt() throws IOException {
        // armeabi-v7a carries no loader32; proot must not be told about one that is not there.
        writeLoader("libproot-loader.so");

        Map<String, String> expected = expectedBase();
        expected.put("PROOT_LOADER", new File(nativeDir, "libproot-loader.so").getAbsolutePath());

        assertEquals(expected, build());
        assertFalse(build().containsKey("PROOT_LOADER_32"));
    }

    @Test
    public void noLoadersFallsBackToPrefixOnly() {
        // Neither loader on disk: proot resolves through PREFIX, so the keys stay absent
        // rather than being set to a path that does not exist.
        assertEquals(expectedBase(), build());
    }

    @Test
    public void everyValueIsPinnedNotJustTheKeys() throws IOException {
        writeLoader("libproot-loader.so");
        Map<String, String> env = build();

        // Guest-side identity and locale: a login shell inside the rootfs relies on these.
        assertEquals("/root", env.get("HOME"));
        assertEquals("root", env.get("USER"));
        assertEquals("root", env.get("LOGNAME"));
        assertEquals("C.UTF-8", env.get("LANG"));
        assertEquals("xterm-256color", env.get("TERM"));
        // TMPDIR is the guest path; PROOT_TMP_DIR is the host one. They are not interchangeable.
        assertEquals("/tmp", env.get("TMPDIR"));
        assertEquals("/data/app/files/proot_tmp", env.get("PROOT_TMP_DIR"));
        assertEquals(ProotEnvironment.GUEST_PATH, env.get("PATH"));
    }

    @Test
    public void carriesNothingBeyondTheShippedVariables() throws IOException {
        // The launch runs over a cleared environment, so anything extra here would be a new
        // variable reaching the guest that never did before.
        writeLoader("libproot-loader.so");
        writeLoader("libproot-loader32.so");
        assertEquals(11, build().size());
    }
}
