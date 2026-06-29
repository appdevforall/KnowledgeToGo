package org.iiab.controller.sync.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;

import org.junit.Test;

/** Unit tests for {@link SecretStore} (S14 step 3 / S11). Pure JVM file I/O. */
public class SecretStoreTest {

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()));
    }

    @Test
    public void writesServerSecretsAsUserColonPass() throws Exception {
        File dir = Files.createTempDirectory("secrets").toFile();
        SecretStore store = new SecretStore(dir);
        File f = store.writeServerSecrets("iiab_peer", "s3cret");
        assertTrue(f.exists());
        assertEquals("iiab_peer:s3cret", read(f));
    }

    @Test
    public void writesClientPasswordContentOnly() throws Exception {
        File dir = Files.createTempDirectory("secrets").toFile();
        SecretStore store = new SecretStore(dir);
        File f = store.writeClientPassword("p@ss");
        assertEquals("p@ss", read(f));
    }

    @Test
    public void clearRemovesBothSecrets() throws Exception {
        File dir = Files.createTempDirectory("secrets").toFile();
        SecretStore store = new SecretStore(dir);
        File server = store.writeServerSecrets("u", "p");
        File client = store.writeClientPassword("p");
        assertTrue(server.exists() && client.exists());
        store.clear();
        assertFalse(server.exists());
        assertFalse(client.exists());
    }

    @Test
    public void deleteClientPasswordLeavesServerSecrets() throws Exception {
        File dir = Files.createTempDirectory("secrets").toFile();
        SecretStore store = new SecretStore(dir);
        File server = store.writeServerSecrets("u", "p");
        store.writeClientPassword("p");
        store.deleteClientPassword();
        assertTrue(server.exists());
        assertFalse(new File(dir, "rsync_client.pass").exists());
    }
}
