package com.emre.linewatch.storage;

import com.emre.linewatch.collector.LineSample;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class SampleRepository {

    private final Database db;

    public SampleRepository(Database db) {
        this.db = db;
    }

    public synchronized void insert(LineSample s) throws SQLException {
        try (PreparedStatement ps = db.conn().prepareStatement("""
                INSERT OR REPLACE INTO samples VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""")) {
            int i = 1;
            ps.setLong(i++, s.ts());
            ps.setString(i++, s.status());
            ps.setString(i++, s.modulation());
            ps.setInt(i++, s.syncDown()); ps.setInt(i++, s.syncUp());
            ps.setInt(i++, s.maxRateDown()); ps.setInt(i++, s.maxRateUp());
            ps.setInt(i++, s.snrDown()); ps.setInt(i++, s.snrUp());
            ps.setInt(i++, s.attenDown()); ps.setInt(i++, s.attenUp());
            ps.setInt(i++, s.powerDown()); ps.setInt(i++, s.powerUp());
            ps.setLong(i++, s.crcDown()); ps.setLong(i++, s.crcUp());
            ps.setLong(i++, s.fecDown()); ps.setLong(i++, s.fecUp());
            ps.setLong(i++, s.hecDown()); ps.setLong(i++, s.hecUp());
            ps.setLong(i++, s.erroredSecs()); ps.setLong(i, s.severelyErroredSecs());
            ps.executeUpdate();
        }
    }

    /**
     * Zaman araligindaki ornekleri dondurur; nokta sayisini sinirlamak icin
     * zaman kovalarina gore ortalama alir (grafikler icin).
     */
    public synchronized List<LineSample> findRange(long from, long to, int maxPoints) throws SQLException {
        long bucket = Math.max(1, (to - from) / Math.max(1, maxPoints));
        List<LineSample> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn().prepareStatement("""
                SELECT MAX(ts), status, modulation,
                       AVG(sync_down), AVG(sync_up), AVG(max_down), AVG(max_up),
                       AVG(snr_down), AVG(snr_up), AVG(atten_down), AVG(atten_up),
                       AVG(power_down), AVG(power_up),
                       MAX(crc_down), MAX(crc_up), MAX(fec_down), MAX(fec_up),
                       MAX(hec_down), MAX(hec_up), MAX(errored_secs), MAX(sev_errored_secs)
                FROM samples WHERE ts BETWEEN ? AND ?
                GROUP BY (ts - ?) / ? ORDER BY 1""")) {
            ps.setLong(1, from);
            ps.setLong(2, to);
            ps.setLong(3, from);
            ps.setLong(4, bucket);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new LineSample(
                            rs.getLong(1), rs.getString(2), rs.getString(3),
                            (int) rs.getDouble(4), (int) rs.getDouble(5),
                            (int) rs.getDouble(6), (int) rs.getDouble(7),
                            (int) rs.getDouble(8), (int) rs.getDouble(9),
                            (int) rs.getDouble(10), (int) rs.getDouble(11),
                            (int) rs.getDouble(12), (int) rs.getDouble(13),
                            rs.getLong(14), rs.getLong(15), rs.getLong(16), rs.getLong(17),
                            rs.getLong(18), rs.getLong(19), rs.getLong(20), rs.getLong(21)));
                }
            }
        }
        return out;
    }

    /** Uptime seridi icin: (ts, up) ciftleri. */
    public synchronized List<long[]> findStatusSince(long ts) throws SQLException {
        List<long[]> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn().prepareStatement(
                "SELECT ts, status FROM samples WHERE ts >= ? ORDER BY ts")) {
            ps.setLong(1, ts);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new long[]{rs.getLong(1), "Up".equalsIgnoreCase(rs.getString(2)) ? 1 : 0});
                }
            }
        }
        return out;
    }

    /** Isi haritasi icin: (ts, crc_down) ciftleri; delta hesabi cagiran tarafta. */
    public synchronized List<long[]> findCrcPointsSince(long ts) throws SQLException {
        List<long[]> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn().prepareStatement(
                "SELECT ts, crc_down FROM samples WHERE ts >= ? ORDER BY ts")) {
            ps.setLong(1, ts);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new long[]{rs.getLong(1), rs.getLong(2)});
            }
        }
        return out;
    }

    /** Rapor icin: araliktaki (ts, crc_down) ciftleri, eskiden yeniye. */
    public synchronized List<long[]> findCrcPoints(long from, long to) throws SQLException {
        List<long[]> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn().prepareStatement(
                "SELECT ts, crc_down FROM samples WHERE ts BETWEEN ? AND ? ORDER BY ts")) {
            ps.setLong(1, from);
            ps.setLong(2, to);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new long[]{rs.getLong(1), rs.getLong(2)});
            }
        }
        return out;
    }

    /**
     * Bunun altindaki SNR okumalari gecersiz sayilir (dB x10). Modem hat "Up" iken
     * ara sira -510 (-51.0 dB) gibi bir isaret degeri donduruyor; gercek bir marj degil.
     */
    public static final int MIN_VALID_SNR = -100;

    /** Saklanan en eski orneklemin zamani; tablo bossa Long.MAX_VALUE. */
    public synchronized long firstTs() throws SQLException {
        try (PreparedStatement ps = db.conn().prepareStatement("SELECT MIN(ts) FROM samples")) {
            long v = readTs(ps);
            return v < 0 ? Long.MAX_VALUE : v;
        }
    }

    /** Rapor icin: ts'den sonraki ilk "Up" orneklemin zamani; yoksa -1. */
    public synchronized long firstUpAfter(long ts) throws SQLException {
        try (PreparedStatement ps = db.conn().prepareStatement(
                "SELECT MIN(ts) FROM samples WHERE ts > ? AND LOWER(status) = 'up'")) {
            ps.setLong(1, ts);
            return readTs(ps);
        }
    }

    /** Rapor icin: [from, to) araligindaki son "Up" olmayan orneklemin zamani; yoksa -1. */
    public synchronized long lastDownBetween(long from, long to) throws SQLException {
        try (PreparedStatement ps = db.conn().prepareStatement(
                "SELECT MAX(ts) FROM samples WHERE ts >= ? AND ts < ? AND LOWER(status) <> 'up'")) {
            ps.setLong(1, from);
            ps.setLong(2, to);
            return readTs(ps);
        }
    }

    private static long readTs(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return -1;
            long v = rs.getLong(1);
            return rs.wasNull() ? -1 : v;
        }
    }

    /**
     * Rapor icin aralik ozeti. SNR ve hiz ortalamalari yalnizca hattin "Up"
     * oldugu orneklemlerden alinir; kopukken modem 0 dondurur ve ortalamayi bozar.
     */
    public synchronized LineStats stats(long from, long to) throws SQLException {
        try (PreparedStatement ps = db.conn().prepareStatement("""
                SELECT COUNT(*),
                       SUM(CASE WHEN LOWER(status) = 'up' THEN 1 ELSE 0 END),
                       AVG(CASE WHEN LOWER(status) = 'up' AND snr_down > ? THEN snr_down END),
                       MIN(CASE WHEN LOWER(status) = 'up' AND snr_down > ? THEN snr_down END),
                       AVG(CASE WHEN LOWER(status) = 'up' THEN snr_up END),
                       AVG(CASE WHEN LOWER(status) = 'up' THEN sync_down END),
                       MIN(CASE WHEN LOWER(status) = 'up' THEN sync_down END),
                       AVG(CASE WHEN LOWER(status) = 'up' THEN sync_up END),
                       AVG(CASE WHEN LOWER(status) = 'up' THEN max_down END),
                       AVG(CASE WHEN LOWER(status) = 'up' THEN atten_down END),
                       MAX(modulation)
                FROM samples WHERE ts BETWEEN ? AND ?""")) {
            ps.setInt(1, MIN_VALID_SNR);
            ps.setInt(2, MIN_VALID_SNR);
            ps.setLong(3, from);
            ps.setLong(4, to);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getInt(1) == 0) return LineStats.EMPTY;
                return new LineStats(rs.getInt(1), rs.getInt(2),
                        rs.getDouble(3) / 10.0, rs.getDouble(4) / 10.0, rs.getDouble(5) / 10.0,
                        rs.getDouble(6), rs.getInt(7), rs.getDouble(8), rs.getDouble(9),
                        rs.getDouble(10) / 10.0, rs.getString(11) == null ? "" : rs.getString(11));
            }
        }
    }

    /** DSL olcumlerinin aralik ozeti; SNR/zayiflama dB, hizlar kbps. */
    public record LineStats(int samples, int upSamples,
                            double avgSnrDownDb, double minSnrDownDb, double avgSnrUpDb,
                            double avgSyncDown, int minSyncDown, double avgSyncUp,
                            double avgMaxDown, double avgAttenDownDb, String modulation) {

        public static final LineStats EMPTY = new LineStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "");

        /** Hat hic "Up" gorulmediyse SNR/hiz degerleri anlamsizdir. */
        public boolean hasUpData() {
            return upSamples > 0;
        }
    }

    /** Verilen andan sonraki ilk orneklemin CRC degeri (gunluk delta hesabi icin); yoksa -1. */
    public synchronized long firstCrcSince(long ts) throws SQLException {
        try (PreparedStatement ps = db.conn().prepareStatement(
                "SELECT crc_down FROM samples WHERE ts >= ? ORDER BY ts LIMIT 1")) {
            ps.setLong(1, ts);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    public synchronized void deleteOlderThan(long ts) throws SQLException {
        try (PreparedStatement ps = db.conn().prepareStatement("DELETE FROM samples WHERE ts < ?")) {
            ps.setLong(1, ts);
            ps.executeUpdate();
        }
    }
}
