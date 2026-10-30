package com.emre.linewatch.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Gercek %APPDATA% kullanilmaz: ayar dosyasi gecici klasorde. */
class SecretStoreTest {

    @Test
    void roundTripUsesDpapiPrefix() {
        assumeTrue(SecretStore.isAvailable(), "DPAPI yalnizca Windows'ta");
        String enc = SecretStore.protect("gizli-Şifre_123");
        assertTrue(enc.startsWith("dpapi:"), enc);
        assertFalse(enc.contains("gizli"));
        assertEquals("gizli-Şifre_123", SecretStore.reveal(enc));
    }

    @Test
    void legacyPlaintextIsAccepted() {
        assertEquals("eski-sifre", SecretStore.reveal("eski-sifre"));
        assertEquals("", SecretStore.reveal(""));
        assertEquals("", SecretStore.reveal(null));
        assertEquals("", SecretStore.protect(""));
    }

    @Test
    void corruptBlobDoesNotThrow() {
        assertEquals("", SecretStore.reveal("dpapi:bm90LWEtYmxvYg=="));
        assertEquals("", SecretStore.reveal("dpapi:%%%gecersiz-base64"));
    }

    @Test
    void settingsStoreSecretsEncrypted(@TempDir Path dir) throws Exception {
        assumeTrue(SecretStore.isAvailable(), "DPAPI yalnizca Windows'ta");
        Path file = dir.resolve("config.properties");
        Settings s = Settings.load(file);
        s.modemPassword("modem-pw");
        s.telegramToken("123:ABC");
        s.save();

        String text = Files.readString(file);
        assertFalse(text.contains("modem-pw"));
        assertFalse(text.contains("ABC"));

        Settings back = Settings.load(file);
        assertEquals("modem-pw", back.modemPassword());
        assertEquals("123:ABC", back.telegramToken());
        assertFalse(back.migrateSecrets(), "zaten sifreli");
    }

    @Test
    void legacyPlaintextSettingsAreMigrated(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.properties");
        Properties legacy = new Properties();
        legacy.setProperty("modem.password", "duz-sifre");
        legacy.setProperty("telegram.token", "999:XYZ");
        try (OutputStream out = Files.newOutputStream(file)) {
            legacy.store(out, null);
        }

        Settings s = Settings.load(file);
        assertEquals("duz-sifre", s.modemPassword());
        assertEquals("999:XYZ", s.telegramToken());

        boolean changed = s.migrateSecrets();
        assertEquals(SecretStore.isAvailable(), changed);
        s.save();

        Settings back = Settings.load(file);
        assertEquals("duz-sifre", back.modemPassword());
        assertEquals("999:XYZ", back.telegramToken());
        if (SecretStore.isAvailable()) {
            assertFalse(Files.readString(file).contains("duz-sifre"));
        }
    }
}
