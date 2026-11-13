package org.appdevforall.k2go.backup.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.appdevforall.k2go.backup.domain.BackupCompanion;
import org.junit.Test;

public class BackupCompanionJsonTest {

    private static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private static BackupCompanion sample() {
        return new BackupCompanion("2026-11-13T10:00:00Z", "1.1.0-abc", "arm64-v8a", null,
                1000L, SHA, 5000L);
    }

    @Test
    public void k2go571_debArchRoundTripsAndOldFilesStillRead() throws Exception {
        BackupCompanion c = BackupCompanionJson.read(BackupCompanionJson.write(new BackupCompanion(
                "2026-11-13T10:00:00Z", "1.2.0", "armeabi-v7a", "armhf", null, 1000L, SHA, 5000L)));
        assertEquals("armeabi-v7a", c.abi);
        assertEquals("armhf", c.debArch);
        // A file written before deb_arch existed still reads, with no deb_arch.
        BackupCompanion old = BackupCompanionJson.read(BackupCompanionJson.write(sample()));
        assertNull(old.debArch);
        assertTrue(old.isComplete());
    }

    @Test
    public void roundTrip() throws Exception {
        BackupCompanion c = BackupCompanionJson.read(BackupCompanionJson.write(sample()));
        assertEquals("arm64-v8a", c.abi);
        assertNull(c.tier);
        assertEquals(1000L, c.gzBytes);
        assertEquals(5000L, c.tarBytes);
        assertTrue(c.expected().matches(1000L, SHA.toUpperCase()));
        assertFalse(c.expected().matches(999L, SHA));
        assertFalse(c.expected().matches(1000L, SHA.replace('0', '1')));
    }

    @Test
    public void rejectsOtherFormatsAndMissingFields() {
        assertNull(BackupCompanionJson.read("{}"));
        assertNull(BackupCompanionJson.read("not json"));
        assertNull(BackupCompanionJson.read(null));
        assertNull(BackupCompanionJson.read("{\"format\":\"k2go-backup/2\",\"created\":\"x\",\"app_version\":\"v\","
                + "\"abi\":\"a\",\"gz_bytes\":1,\"gz_sha256\":\"" + SHA + "\",\"tar_bytes\":1}"));
        assertNull(BackupCompanionJson.read("{\"format\":\"k2go-backup/1\",\"created\":\"x\",\"app_version\":\"v\","
                + "\"abi\":\"a\",\"gz_bytes\":1,\"gz_sha256\":\"abc\",\"tar_bytes\":1}"));
        assertNull(BackupCompanionJson.read("{\"format\":\"k2go-backup/1\",\"created\":\"x\",\"app_version\":\"v\","
                + "\"abi\":\"a\",\"gz_bytes\":1,\"gz_sha256\":\"" + SHA + "\"}"));
    }
}
