/*
 * ============================================================================
 * Name        : KeystoreTeacherKey.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School trust: the teacher key of this phone, in the Android Keystore.
 * ============================================================================
 */
package org.appdevforall.k2go.school.trust.data;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import org.appdevforall.k2go.school.trust.domain.Base64Url;
import org.appdevforall.k2go.school.trust.domain.SchoolRing;

import java.security.Key;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.spec.ECGenParameterSpec;

/**
 * The private key never leaves the Keystore: not the app, not the developer terminal (same Linux
 * user) can copy it (maintainer, 2026-11-26). Android deletes it with the app data. It signs the
 * class vouch of each class (VouchClass); the box signs each class proof with the class key.
 */
public final class KeystoreTeacherKey implements SchoolRing.TeacherKey {

    private static final String TAG = "K2GoSchool";
    static final String ALIAS = "k2go-school-teacher";

    @Override
    public synchronized String publicKey() {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (!ks.containsAlias(ALIAS)) {
                KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
                g.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build());
                g.generateKeyPair();
            }
            Certificate c = ks.getCertificate(ALIAS);
            return c == null ? null : Base64Url.encodeStandard(c.getPublicKey().getEncoded());
        } catch (Exception e) {
            Log.w(TAG, "teacher key not available", e);
            return null;
        }
    }

    /** Signs with the key in the Keystore. It never makes a key: no key, no signature. */
    @Override
    public synchronized byte[] sign(byte[] data) {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            Key k = ks.getKey(ALIAS, null);
            if (!(k instanceof PrivateKey)) return null;
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initSign((PrivateKey) k);
            s.update(data);
            return s.sign();
        } catch (Exception e) {
            Log.w(TAG, "teacher key did not sign", e);
            return null;
        }
    }
}
