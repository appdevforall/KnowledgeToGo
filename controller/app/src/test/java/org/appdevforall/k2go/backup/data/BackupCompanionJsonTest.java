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

    @Test
    public void companionNames() {
        assertEquals("k2go_2026.317_1_aarch64.tar.json", BackupCompanion.companionName("k2go_2026.317_1_aarch64.tar.gz"));
        assertEquals("k2go_2026.317_1_aarch64.tar.gz", BackupCompanion.tarballName("k2go_2026.317_1_aarch64.tar.json"));
        assertNull(BackupCompanion.companionName("backup.zip"));
        assertNull(BackupCompanion.companionName(".tar.gz"));
        assertNull(BackupCompanion.tarballName("backup.json"));
    }
}
