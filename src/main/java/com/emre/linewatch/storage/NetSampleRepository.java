package com.emre.linewatch.storage;

import com.emre.linewatch.collector.NetSample;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** net_samples tablosu: internet gecikme / jitter / paket kaybi olcumleri. */
public class NetSampleRepository {

    private final Database db;

    public NetSampleRepository(Database db) {
        this.db = db;
    }

    public synchronized void insert(NetSample s) throws SQLException {
        try (PreparedStatement ps = db.conn().prepareStatement(
                "INSERT OR REPLACE INTO net_samples VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setLong(1, s.ts());
            ps.setInt(2, s.online() ? 1 : 0);
            ps.setDouble(3, s.latencyMs());
            ps.setDouble(4, s.jitterMs());
            ps.setInt(5, s.lossPct());
            ps.setInt(6, s.sent());
            ps.setInt(7, s.received());
            ps.setString(8, s.target());
            ps.executeUpdate();
        }
    }

    /**
     * Zaman araligindaki olcumler; grafik icin nokta sayisi kovalanarak sinirlanir.
     *
     * <p>Olculemeyen gecikme -1 olarak saklandigi icin ortalamaya katilmamali:
     * AVG yerine {@code AVG(CASE WHEN ... >= 0 ...)} kullaniliyor, yoksa cevrimdisi
     * araliklar ortalamayi asagi cekip grafigi bozuyor.
     */
    public synchronized List<NetSample> findRange(long from, long to, int maxPoints) throws SQLException {
        long bucket = Math.max(1, (to - from) / Math.max(1, maxPoints));
        List<NetSample> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn().prepareStatement("""
                SELECT MAX(ts),
                       MIN(online),
                       AVG(CASE WHEN latency_ms >= 0 THEN latency_ms END),
                       AVG(CASE WHEN jitter_ms  >= 0 THEN jitter_ms  END),
                       AVG(loss_pct), SUM(sent), SUM(received), MAX(target)
                FROM net_samples WHERE ts BETWEEN ? AND ?
                GROUP BY (ts - ?) / ? ORDER BY 1""")) {
            ps.setLong(1, from);
            ps.setLong(2, to);
            ps.setLong(3, from);
            ps.setLong(4, bucket);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double latency = rs.getDouble(3);
                    if (rs.wasNull()) latency = -1;
                    double jitter = rs.getDouble(4);
                    if (rs.wasNull()) jitter = -1;
                    out.add(new NetSample(rs.getLong(1), rs.getInt(2) == 1, latency, jitter,
                            (int) Math.round(rs.getDouble(5)), rs.getInt(6), rs.getInt(7),
                            rs.getString(8) == null ? "" : rs.getString(8)));
                }
            }
        }
        return out;
    }

    /** Uptime seridi icin: (ts, online) ciftleri. */
    public synchronized List<long[]> findStatusSince(long ts) throws SQLException {
        List<long[]> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn().prepareStatement(
                "SELECT ts, online FROM net_samples WHERE ts >= ? ORDER BY ts")) {
            ps.setLong(1, ts);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new long[]{rs.getLong(1), rs.getLong(2)});
            }
        }
        return out;
    }

    /**
     * Verilen andan bu yana ozet: ortalama gecikme, ortalama jitter, ortalama kayip
     * ve cevrimici olcum orani (%). Hic kayit yoksa tum alanlar -1 / 0.
     */
    public synchronized Summary summarySince(long ts) throws SQLException {
        try (PreparedStatement ps = db.conn().prepareStatement("""
                SELECT AVG(CASE WHEN latency_ms >= 0 THEN latency_ms END),
                       AVG(CASE WHEN jitter_ms  >= 0 THEN jitter_ms  END),
                       AVG(loss_pct),
                       AVG(online) * 100.0,
                       COUNT(*)
                FROM net_samples WHERE ts >= ?""")) {
            ps.setLong(1, ts);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Summary.empty();
                int count = rs.getInt(5);
                if (count == 0) return Summary.empty();
                double latency = rs.getDouble(1);
                if (rs.wasNull()) latency = -1;
                double jitter = rs.getDouble(2);
                if (rs.wasNull()) jitter = -1;
                return new Summary(latency, jitter, rs.getDouble(3), rs.getDouble(4), count);
            }
        }
    }

    public synchronized void deleteOlderThan(long ts) throws SQLException {
        try (PreparedStatement ps = db.conn().prepareStatement("DELETE FROM net_samples WHERE ts < ?")) {
            ps.setLong(1, ts);
            ps.executeUpdate();
        }
    }

    /** Bir zaman araligindaki internet kalitesi ozeti. */
    public record Summary(double avgLatencyMs, double avgJitterMs, double avgLossPct,
                          double onlinePct, int samples) {

        public static Summary empty() {
            return new Summary(-1, -1, 0, 0, 0);
        }

        public boolean hasData() {
            return samples > 0;
        }
    }
}
