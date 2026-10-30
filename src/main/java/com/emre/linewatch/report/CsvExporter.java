package com.emre.linewatch.report;

import com.emre.linewatch.storage.Database;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Ham olcumleri CSV olarak disa aktarir (RFC 4180: virgul ayrac, nokta ondalik).
 * Excel'in UTF-8'i tanimasi icin dosya BOM ile baslar.
 *
 * <p>Modemden x10 gelen SNR/zayiflama/guc degerleri burada gercek birimlerine
 * cevrilir; olculemeyen gecikme (-1) bos hucre olarak yazilir.
 */
public class CsvExporter {

    private static final String DSL_SQL = """
            SELECT ts, status, modulation,
                   sync_down AS sync_down_kbps, sync_up AS sync_up_kbps,
                   max_down AS max_down_kbps, max_up AS max_up_kbps,
                   snr_down / 10.0 AS snr_down_db, snr_up / 10.0 AS snr_up_db,
                   atten_down / 10.0 AS atten_down_db, atten_up / 10.0 AS atten_up_db,
                   power_down / 10.0 AS power_down_dbm, power_up / 10.0 AS power_up_dbm,
                   crc_down, crc_up, fec_down, fec_up, hec_down, hec_up,
                   errored_secs, sev_errored_secs
            FROM samples WHERE ts BETWEEN ? AND ? ORDER BY ts""";

    private static final String NET_SQL = """
            SELECT ts, online,
                   CASE WHEN latency_ms >= 0 THEN ROUND(latency_ms, 2) END AS latency_ms,
                   CASE WHEN jitter_ms  >= 0 THEN ROUND(jitter_ms, 2)  END AS jitter_ms,
                   loss_pct, sent, received, target
            FROM net_samples WHERE ts BETWEEN ? AND ? ORDER BY ts""";

    private static final String EVENTS_SQL = """
            SELECT ts, type, duration_sec, detail
            FROM events WHERE ts BETWEEN ? AND ? ORDER BY ts""";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Database db;
    private final ZoneId zone;

    public CsvExporter(Database db) {
        this(db, ZoneId.systemDefault());
    }

    CsvExporter(Database db, ZoneId zone) {
        this.db = db;
        this.zone = zone;
    }

    /**
     * Araliktaki veriyi {@code <prefix>-dsl.csv}, {@code -internet.csv} ve
     * {@code -events.csv} olarak yazar. Bos tablolar icin dosya olusturulmaz.
     *
     * @return yazilan dosyalar
     */
    public List<Path> export(long from, long to, Path dir, String prefix) throws IOException, SQLException {
        Files.createDirectories(dir);
        List<Path> written = new ArrayList<>();
        write(DSL_SQL, from, to, dir.resolve(prefix + "-dsl.csv"), written);
        write(NET_SQL, from, to, dir.resolve(prefix + "-internet.csv"), written);
        write(EVENTS_SQL, from, to, dir.resolve(prefix + "-events.csv"), written);
        return written;
    }

    private void write(String sql, long from, long to, Path file, List<Path> written)
            throws IOException, SQLException {
        try (PreparedStatement ps = db.conn().prepareStatement(sql)) {
            ps.setLong(1, from);
            ps.setLong(2, to);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return;
                ResultSetMetaData md = rs.getMetaData();
                int cols = md.getColumnCount();
                try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    w.write('﻿');
                    // ilk kolon her zaman ts (epoch ms); okunabilir yerel saat basa eklenir
                    w.write("time");
                    for (int c = 1; c <= cols; c++) {
                        w.write(',');
                        w.write(c == 1 ? "ts_ms" : md.getColumnLabel(c));
                    }
                    w.write("\r\n");
                    do {
                        w.write(TIME.format(Instant.ofEpochMilli(rs.getLong(1)).atZone(zone)));
                        for (int c = 1; c <= cols; c++) {
                            w.write(',');
                            w.write(cell(rs.getString(c)));
                        }
                        w.write("\r\n");
                    } while (rs.next());
                }
                written.add(file);
            }
        }
    }

    /** Virgul, tirnak ya da satir sonu iceren hucreler tirnak icine alinir. */
    static String cell(String v) {
        if (v == null) return "";
        if (v.indexOf(',') < 0 && v.indexOf('"') < 0 && v.indexOf('\n') < 0 && v.indexOf('\r') < 0) return v;
        return '"' + v.replace("\"", "\"\"") + '"';
    }
}
