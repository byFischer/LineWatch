package com.emre.linewatch.storage;

import com.emre.linewatch.config.Settings;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

public class Database {

    /**
     * Sema surumleri, sirayla. Indeks + 1 = surum numarasi (PRAGMA user_version).
     * Eski veritabanlari surum 0 olarak gorunur ama surum 1 tablolarini zaten
     * icerir; bu yuzden her adim tekrar calistirilabilir olmali (IF NOT EXISTS).
     * Yeni bir degisiklik her zaman listenin sonuna yeni bir adim olarak eklenir.
     */
    private static final List<String[]> MIGRATIONS = List.of(
            // 1: ilk sema — DSL olcumleri, olaylar, internet olcumleri
            new String[]{
                    """
                    CREATE TABLE IF NOT EXISTS samples(
                      ts INTEGER PRIMARY KEY,
                      status TEXT, modulation TEXT,
                      sync_down INTEGER, sync_up INTEGER,
                      max_down INTEGER, max_up INTEGER,
                      snr_down INTEGER, snr_up INTEGER,
                      atten_down INTEGER, atten_up INTEGER,
                      power_down INTEGER, power_up INTEGER,
                      crc_down INTEGER, crc_up INTEGER,
                      fec_down INTEGER, fec_up INTEGER,
                      hec_down INTEGER, hec_up INTEGER,
                      errored_secs INTEGER, sev_errored_secs INTEGER)""",
                    """
                    CREATE TABLE IF NOT EXISTS events(
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      ts INTEGER NOT NULL,
                      type TEXT NOT NULL,
                      detail TEXT,
                      duration_sec INTEGER)""",
                    "CREATE INDEX IF NOT EXISTS idx_events_ts ON events(ts)",
                    // Internet erisim olcumleri; DSL orneklerinden ayri tutulur cunku
                    // modem desteklenmese de toplanmaya devam eder.
                    """
                    CREATE TABLE IF NOT EXISTS net_samples(
                      ts INTEGER PRIMARY KEY,
                      online INTEGER NOT NULL,
                      latency_ms REAL,
                      jitter_ms REAL,
                      loss_pct INTEGER,
                      sent INTEGER, received INTEGER,
                      target TEXT)"""
            },
            // 2: saatlik ozetler — ham olcumlerden cok daha uzun saklanir (bkz. RetentionJob).
            // Ortalama yerine toplam + adet tutulur: araliklar birlestirilirken sonuc kesin kalir.
            new String[]{
                    """
                    CREATE TABLE IF NOT EXISTS samples_hourly(
                      hour INTEGER PRIMARY KEY,
                      samples INTEGER NOT NULL, up_samples INTEGER NOT NULL,
                      snr_down_sum INTEGER, snr_down_n INTEGER, snr_down_min INTEGER,
                      snr_up_sum INTEGER, snr_up_n INTEGER,
                      sync_down_sum INTEGER, sync_down_n INTEGER, sync_down_min INTEGER,
                      sync_up_sum INTEGER, sync_up_n INTEGER,
                      max_down_sum INTEGER, max_down_n INTEGER,
                      atten_down_sum INTEGER, atten_down_n INTEGER,
                      modulation TEXT,
                      crc_inc INTEGER NOT NULL)""",
                    """
                    CREATE TABLE IF NOT EXISTS net_hourly(
                      hour INTEGER PRIMARY KEY,
                      samples INTEGER NOT NULL, online_samples INTEGER NOT NULL,
                      latency_sum REAL, latency_n INTEGER,
                      jitter_sum REAL, jitter_n INTEGER,
                      loss_sum INTEGER)"""
            });

    /** Kodun bekledigi sema surumu. */
    public static final int SCHEMA_VERSION = MIGRATIONS.size();

    private final Connection conn;

    public Database() throws SQLException, IOException {
        this(defaultPath());
    }

    public Database(String jdbcTarget) throws SQLException, IOException {
        conn = DriverManager.getConnection("jdbc:sqlite:" + jdbcTarget);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
        }
        migrate();
    }

    /** Eksik sema adimlarini sirayla, her birini kendi islemi icinde uygular. */
    private void migrate() throws SQLException {
        int version = schemaVersion();
        for (int v = version + 1; v <= MIGRATIONS.size(); v++) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                for (String sql : MIGRATIONS.get(v - 1)) st.execute(sql);
                st.execute("PRAGMA user_version = " + v);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /** Veritabaninin kayitli sema surumu (PRAGMA user_version). */
    public int schemaVersion() throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static String defaultPath() throws IOException {
        Files.createDirectories(Settings.DIR);
        return Settings.DIR.resolve("linewatch.db").toString();
    }

    public Connection conn() {
        return conn;
    }

    public void close() {
        try { conn.close(); } catch (SQLException ignored) {}
    }
}
