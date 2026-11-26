package org.appdevforall.k2go.school.trust.domain;

import java.nio.charset.StandardCharsets;

/** Test access to the package-private PBKDF2 of KeyVault. */
public final class KeyVaultAccess {

    private KeyVaultAccess() {}

    public static byte[] pbkdf2(String password, String salt, int iterations) {
        return KeyVault.pbkdf2(password.getBytes(StandardCharsets.UTF_8), salt.getBytes(StandardCharsets.UTF_8), iterations);
    }
}
