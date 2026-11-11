/*
 * ============================================================================
 * Name        : DeviceIdStoreTest.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-560. Unit tests for DeviceIdStore.
 * ============================================================================
 */
package org.appdevforall.k2go.boxidentity.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class DeviceIdStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.US_ASCII);
    }

    @Test
    public void makesA128BitHexIdOnce() throws IOException {
        File dir = tmp.newFolder();
        String first = read(DeviceIdStore.ensure(dir));
        assertTrue(first.matches("[0-9a-f]{32}"));
        assertEquals(first, read(DeviceIdStore.ensure(dir)));
    }

    @Test
    public void replacesAnInvalidFile() throws IOException {
        File dir = tmp.newFolder();
        try (FileOutputStream out = new FileOutputStream(new File(dir, "k2go-device-id"))) {
            out.write("not-an-id".getBytes(StandardCharsets.US_ASCII));
        }
        String id = read(DeviceIdStore.ensure(dir));
        assertTrue(id.matches("[0-9a-f]{32}"));
    }

    @Test
    public void twoInstallsGetDifferentIds() throws IOException {
        assertNotEquals(read(DeviceIdStore.ensure(tmp.newFolder())),
                read(DeviceIdStore.ensure(tmp.newFolder())));
    }
}
