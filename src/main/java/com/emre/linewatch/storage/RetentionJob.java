package com.emre.linewatch.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static com.emre.linewatch.storage.HourlyRepository.HOUR;
import static com.emre.linewatch.storage.HourlyRepository.floorHour;

/**
 * Uzun sureli saklama: ham olcumleri saatlik ozetlere toplar, sonra eskilerini siler.
 *
 * <p>Sira onemli: once tamamlanmis ve henuz ozetlenmemis tum saatler ozetlenir,
 * ancak ondan sonra ham olcumler silinir — boylece silinen hicbir saat ozetsiz
 * kalmaz. Silme siniri saat basina hizalidir; bir saatin ham verisi ya tamamen
 * durur ya da tamamen silinir. Her adim tek bir SQL ifadesi oldugu icin kendi
 * basina atomiktir ve is tekrar calistirilabilir (INSERT OR REPLACE).
 */
public class RetentionJob {

    private static final long DAY = 24 * HOUR;

    /**
     * Saat basina toplam: ilk ozetlenecek saatten onceki son olcum de pencereye
     * alinir ki CRC artisi saat sinirindaki adimi da kapsasin. Negatif adim sayac
     * sifirlanmasidir (modem yeniden basladi) ve sayilmaz.
     */
    private static final String AGGREGATE_LINE = """
            INSERT OR REPLACE INTO samples_hourly(hour, %s, crc_inc)
            SELECT (ts / 3600000) * 3600000 AS h, %s,
                   COALESCE(SUM(CASE WHEN crc_step > 0 THEN crc_step END), 0)
            FROM (SELECT *, crc_down - LAG(crc_down) OVER (ORDER BY ts) AS crc_step
                  FROM samples
                  WHERE ts >= COALESCE((SELECT MAX(ts) FROM samples WHERE ts < ?), ?) AND ts < ?)
            WHERE ts >= ?
            GROUP BY h""".formatted(LineAggregate.HOURLY_COLUMNS, LineAggregate.COLUMNS);

    private static final String AGGREGATE_NET = """
            INSERT OR REPLACE INTO net_hourly(hour, %s)
            SELECT (ts / 3600000) * 3600000 AS h, %s
            FROM net_samples WHERE ts >= ? AND ts < ?
            GROUP BY h""".formatted(NetAggregate.HOURLY_COLUMNS, NetAggregate.COLUMNS);

    private final Database db;

    public RetentionJob(Database db) {
        this.db = db;
    }

    /** Bir calismanin ozeti (gunluk / test icin). */
    public record Result(int lineHours, int netHours, int samplesDeleted, int netDeleted, int hourlyDeleted) {
    }

    /**
     * @param now                 simdiki zaman; ancak bundan once biten saatler ozetlenir
     * @param rawRetentionDays    ham olcumlerin saklanma suresi
     * @param hourlyRetentionDays saatlik ozetlerin saklanma suresi (ham sureden kisa olamaz)
     */
    public synchronized Result run(long now, int rawRetentionDays, int hourlyRetentionDays) throws SQLException {
        long completeUntil = floorHour(now);
        int lineHours = aggregate(AGGREGATE_LINE, "samples_hourly", "samples", completeUntil, true);
        int netHours = aggregate(AGGREGATE_NET, "net_hourly", "net_samples", completeUntil, false);

        int rawDays = Math.max(1, rawRetentionDays);
        long rawCutoff = Math.min(floorHour(now - rawDays * DAY), completeUntil);
        int samplesDeleted = delete("DELETE FROM samples WHERE ts < ?", rawCutoff);
        int netDeleted = delete("DELETE FROM net_samples WHERE ts < ?", rawCutoff);

        long hourlyCutoff = floorHour(now - Math.max(rawDays, hourlyRetentionDays) * DAY);
        int hourlyDeleted = delete("DELETE FROM samples_hourly WHERE hour < ?", hourlyCutoff)
                + delete("DELETE FROM net_hourly WHERE hour < ?", hourlyCutoff);
        return new Result(lineHours, netHours, samplesDeleted, netDeleted, hourlyDeleted);
    }

    /**
     * Son ozetlenen saatten sonraki (hic yoksa en eski olcumun saatinden itibaren)
     * tamamlanmis saatleri ozetler; yazilan saat sayisini dondurur.
     */
    private int aggregate(String sql, String hourlyTable, String rawTable, long until, boolean withPrevious)
            throws SQLException {
        long start;
        long lastHour = queryLong("SELECT MAX(hour) FROM " + hourlyTable);
        if (lastHour >= 0) {
            start = lastHour + HOUR;
        } else {
            long first = queryLong("SELECT MIN(ts) FROM " + rawTable);
            if (first < 0) return 0;
            start = floorHour(first);
        }
        if (start >= until) return 0;
        try (PreparedStatement ps = db.conn().prepareStatement(sql)) {
            int i = 1;
            if (withPrevious) {
                ps.setLong(i++, start);
                ps.setLong(i++, start);
                ps.setLong(i++, until);
                ps.setLong(i, start);
            } else {
                ps.setLong(i++, start);
                ps.setLong(i, until);
            }
            return ps.executeUpdate();
        }
    }

    private long queryLong(String sql) throws SQLException {
        try (PreparedStatement ps = db.conn().prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return -1;
            long v = rs.getLong(1);
            return rs.wasNull() ? -1 : v;
        }
    }

    private int delete(String sql, long cutoff) throws SQLException {
        try (PreparedStatement ps = db.conn().prepareStatement(sql)) {
            ps.setLong(1, cutoff);
            return ps.executeUpdate();
        }
    }
}
