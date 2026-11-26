/*
 * ============================================================================
 * Name        : SchoolTrustRepository.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School trust: the port to the trust files of this phone.
 * ============================================================================
 */
package org.appdevforall.k2go.school.trust.domain;

import java.security.PrivateKey;

/**
 * The files are the one owner of the trust state: the pinned root (every school phone) and the
 * sealed school key (device zero only). Read on each use, no copy in memory.
 */
public interface SchoolTrustRepository {

    /** The pinned root of this phone, or null: the phone is not part of a school. */
    TrustRecords.Root root();

    /** The sealed school key: present only on device zero. */
    KeyVault.Sealed vault();

    /** The self-signed root token of a new school. */
    String rootToken(PrivateKey key, String rootKeySpkiB64, String school, String name, long until);

    /** Writes the root and the sealed key (device zero). false: not written. */
    boolean saveDeviceZero(String rootToken, KeyVault.Sealed vault);

    /** The backup file: the same bytes as the key file of device zero. null: no key on this phone. */
    byte[] backup();

    /** A backup file that was read back. null: not a backup of a school key. */
    Backup parseBackup(byte[] file);

    /** The content of a backup file: a checked root and its sealed key. */
    final class Backup {
        public final String rootToken;
        public final TrustRecords.Root root;
        public final KeyVault.Sealed vault;

        public Backup(String rootToken, TrustRecords.Root root, KeyVault.Sealed vault) {
            this.rootToken = rootToken;
            this.root = root;
            this.vault = vault;
        }
    }
}
