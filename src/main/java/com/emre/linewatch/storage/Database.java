package com.emre.linewatch.storage;

import com.emre.linewatch.config.Settings;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class Database {

    private final Connection conn;

    public Database() throws SQLException, IOException {
        this(defaultPath());
    }

    public Database(String jdbcTarget) throws SQLException, IOException {
        conn = DriverManager.getConnection("jdbc:sqlite:" + jdbcTarget);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("""
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
                  errored_secs INTEGER, sev_errored_secs INTEGER)""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS events(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  ts INTEGER NOT NULL,
                  type TEXT NOT NULL,
                  detail TEXT,
                  duration_sec INTEGER)""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_events_ts ON events(ts)");
            // Internet erisim olcumleri; DSL orneklerinden ayri tutulur cunku
            // modem desteklenmese de toplanmaya devam eder.
            st.execute("""
                CREATE TABLE IF NOT EXISTS net_samples(
                  ts INTEGER PRIMARY KEY,
                  online INTEGER NOT NULL,
                  latency_ms REAL,
                  jitter_ms REAL,
                  loss_pct INTEGER,
                  sent INTEGER, received INTEGER,
                  target TEXT)""");
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
