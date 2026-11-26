/*
 * ============================================================================
 * Name        : SchoolSetup.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School trust: create the school on device zero, restore its key.
 * ============================================================================
 */
package org.appdevforall.k2go.school.trust.domain;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.Arrays;

/**
 * The rules of device zero (design: school-trust-design.md, 6.1 and 7). One school per phone: a
 * phone that already pins a root cannot make or take the key of another school.
 */
public final class SchoolSetup {

    public enum Result {
        OK, NAME, DATE, PASSWORD_SHORT, PASSWORD_DIFFERENT, ALREADY_IN_SCHOOL,
        NOT_A_BACKUP, WRONG_PASSWORD, OTHER_SCHOOL, FAILED
    }

    /** [decided 2026-11-26] 8 characters or more. Characters, not UTF-16 units. */
    public static final int MIN_PASSWORD = 8;
    public static final int MAX_NAME = 80;
    /** The end of the school year: at most two years from now. */
    public static final long MAX_YEAR_SECONDS = 2L * 366 * 86400;

    private final SchoolTrustRepository repo;

    public SchoolSetup(SchoolTrustRepository repo) {
        this.repo = repo;
    }

    /** The first rule of the form that fails, or OK. The screen shows it before Create. */
    public static Result check(String name, long until, String password, String again, long now) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > MAX_NAME) return Result.NAME;
        for (int i = 0; i < n.length(); i++) if (n.charAt(i) < 0x20) return Result.NAME;
        if (until <= now || until > now + MAX_YEAR_SECONDS) return Result.DATE;
        if (password == null || password.codePointCount(0, password.length()) < MIN_PASSWORD) return Result.PASSWORD_SHORT;
        if (!password.equals(again)) return Result.PASSWORD_DIFFERENT;
        return Result.OK;
    }

    /** Makes the school key on this phone: this phone becomes device zero. Slow (the password key). */
    public Result create(String name, long until, String password, String again, long now) {
        Result form = check(name, until, password, again, now);
        if (form != Result.OK) return form;
        if (repo.root() != null) return Result.ALREADY_IN_SCHOOL;
        KeyPair kp = TrustKeys.generate();
        byte[] spki = kp.getPublic().getEncoded();
        String school = TrustKeys.schoolId(spki);
        byte[] pkcs8 = kp.getPrivate().getEncoded();
        try {
            String token = repo.rootToken(kp.getPrivate(), Base64Url.encodeStandard(spki), school, name.trim(), until);
            KeyVault.Sealed vault = KeyVault.seal(pkcs8, password, school);
            return repo.saveDeviceZero(token, vault) ? Result.OK : Result.FAILED;
        } finally {
            Arrays.fill(pkcs8, (byte) 0);
        }
    }

    /**
     * Puts the school key of a backup on this phone: it becomes device zero. The password must open
     * the key, and the key must belong to the root of the backup.
     */
    public Result restore(byte[] file, String password) {
        SchoolTrustRepository.Backup b = repo.parseBackup(file);
        if (b == null) return Result.NOT_A_BACKUP;
        TrustRecords.Root pinned = repo.root();
        if (pinned != null && !pinned.school.equals(b.root.school)) return Result.OTHER_SCHOOL;
        byte[] pkcs8 = KeyVault.open(b.vault, password, b.root.school);
        if (pkcs8 == null) return Result.WRONG_PASSWORD;
        try {
            PrivateKey key = TrustKeys.privateKey(pkcs8);
            if (!TrustKeys.isPair(key, b.root.keySpki())) return Result.NOT_A_BACKUP;
            return repo.saveDeviceZero(b.rootToken, b.vault) ? Result.OK : Result.FAILED;
        } finally {
            Arrays.fill(pkcs8, (byte) 0);
        }
    }
}
