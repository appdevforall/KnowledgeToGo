package org.iiab.controller.backup.domain;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/** Pure-JVM tests for keeping the exact backup name with -N disambiguation. */
public class BackupNameResolverTest {

    private static Set<String> set(String... names) {
        return new HashSet<>(Arrays.asList(names));
    }

    @Test public void keepsExactNameWhenFree() {
        assertEquals("iiab-backup.tar.gz",
                BackupNameResolver.resolve("iiab-backup.tar.gz", Collections.emptySet()));
    }

    @Test public void appendsDashOneOnCollision() {
        assertEquals("iiab-backup-1.tar.gz",
                BackupNameResolver.resolve("iiab-backup.tar.gz", set("iiab-backup.tar.gz")));
    }

    @Test public void incrementsUntilFree() {
        assertEquals("iiab-backup-3.tar.gz",
                BackupNameResolver.resolve("iiab-backup.tar.gz",
                        set("iiab-backup.tar.gz", "iiab-backup-1.tar.gz", "iiab-backup-2.tar.gz")));
    }

    @Test public void preservesTarXzCompoundExtension() {
        assertEquals("snap-1.tar.xz",
                BackupNameResolver.resolve("snap.tar.xz", set("snap.tar.xz")));
    }

    @Test public void handlesNameWithoutExtension() {
        assertEquals("myfile-1",
                BackupNameResolver.resolve("myfile", set("myfile")));
    }

    @Test public void stripsPathAndFallsBackOnEmptyOrNull() {
        assertEquals("a.tar.gz", BackupNameResolver.resolve("/sdcard/Download/a.tar.gz", Collections.emptySet()));
        assertEquals("backup.tar.gz", BackupNameResolver.resolve(null, Collections.emptySet()));
        assertEquals("backup.tar.gz", BackupNameResolver.resolve("   ", Collections.emptySet()));
    }
}
