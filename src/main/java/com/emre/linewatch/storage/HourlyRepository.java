package com.emre.linewatch.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * samples_hourly / net_hourly tablolari: ham olcumler silindikten sonra eski
 * donemler icin raporun dayandigi saatlik ozetler (bkz. {@link RetentionJob}).
 * Saat baslangiclari epoch'a gore saat basina hizalidir.
 */
public class HourlyRepository {

    public static final long HOUR = 3_600_000L;

    private final Database db;

    public HourlyRepository(Database db) {
        this.db = db;
    }

    /** Bir saatin DSL ozeti; {@code crcInc} o saat icindeki CRC artisi (sayac sifirlanmalari haric). */
    public record LineHour(long hour, LineAggregate agg, long crcInc) {
    }

    /** Bir saatin internet olcumu ozeti. */
    public record NetHour(long hour, NetAggregate agg) {
    }

    public static long floorHour(long ts) {
        return Math.floorDiv(ts, HOUR) * HOUR;
    }

    /** Baslangici [from, to) araliginda olan saatler, eskiden yeniye. */
    public synchronized List<LineHour> lineHours(long from, long to) throws SQLException {
        List<LineHour> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn().prepareStatement(
                "SELECT hour, " + LineAggregate.HOURLY_COLUMNS + ", crc_inc FROM samples_hourly"
                        + " WHERE hour >= ? AND hour < ? ORDER BY hour")) {
            ps.setLong(1, from);
            ps.setLong(2, to);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new LineHour(rs.getLong(1), LineAggregate.read(rs, 2), rs.getLong(19)));
            }
        }
        return out;
    }

    /** Baslangici [from, to) araliginda olan saatler, eskiden yeniye. */
    public synchronized List<NetHour> netHours(long from, long to) throws SQLException {
        List<NetHour> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn().prepareStatement(
                "SELECT hour, " + NetAggregate.HOURLY_COLUMNS + " FROM net_hourly"
                        + " WHERE hour >= ? AND hour < ? ORDER BY hour")) {
            ps.setLong(1, from);
            ps.setLong(2, to);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new NetHour(rs.getLong(1), NetAggregate.read(rs, 2)));
            }
        }
        return out;
    }

    /** En eski DSL ozetinin saati; yoksa Long.MAX_VALUE. */
    public synchronized long firstLineHour() throws SQLException {
        long v = queryTs("SELECT MIN(hour) FROM samples_hourly");
        return v < 0 ? Long.MAX_VALUE : v;
    }

    /** En eski internet ozetinin saati; yoksa Long.MAX_VALUE. */
    public synchronized long firstNetHour() throws SQLException {
        long v = queryTs("SELECT MIN(hour) FROM net_hourly");
        return v < 0 ? Long.MAX_VALUE : v;
    }

    /**
     * {@code ts}'yi iceren saatten sonraki, {@code before}'dan once baslayan ve icinde
     * "Up" olcum bulunan ilk saat; yoksa -1. ts'nin kendi saati sayilmaz: o saatteki
     * "Up" olcumun ts'den once mi sonra mi oldugu bilinemez.
     */
    public synchronized long firstUpHourAfter(long ts, long before) throws SQLException {
        return queryTs("SELECT MIN(hour) FROM samples_hourly WHERE hour > ? AND hour < ? AND up_samples > 0",
                floorHour(ts), before);
    }

    /** {@code from}'u iceren saatten sonra, {@code to}'dan once baslayan, kopuk olcum iceren son saat; yoksa -1. */
    public synchronized long lastDownHourBetween(long from, long to) throws SQLException {
        return queryTs("SELECT MAX(hour) FROM samples_hourly WHERE hour > ? AND hour < ? AND samples > up_samples",
                floorHour(from), to);
    }

    /** {@link #firstUpHourAfter} internet olcumleri icin (cevrimici olcum iceren ilk saat). */
    public synchronized long firstOnlineHourAfter(long ts, long before) throws SQLException {
        return queryTs("SELECT MIN(hour) FROM net_hourly WHERE hour > ? AND hour < ? AND online_samples > 0",
                floorHour(ts), before);
    }

    /** {@link #lastDownHourBetween} internet olcumleri icin. */
    public synchronized long lastOfflineHourBetween(long from, long to) throws SQLException {
        return queryTs("SELECT MAX(hour) FROM net_hourly WHERE hour > ? AND hour < ? AND samples > online_samples",
                floorHour(from), to);
    }

    private long queryTs(String sql, long... params) throws SQLException {
        try (PreparedStatement ps = db.conn().prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setLong(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return -1;
                long v = rs.getLong(1);
                return rs.wasNull() ? -1 : v;
            }
        }
    }
}
