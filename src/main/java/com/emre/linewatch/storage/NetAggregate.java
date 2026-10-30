package com.emre.linewatch.storage;

import com.emre.linewatch.collector.NetSample;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Internet olcumlerinin toplamlari (bkz. {@link LineAggregate}). Olculemeyen
 * gecikme/jitter -1 saklandigi icin yalnizca 0 ve ustu degerler toplanir.
 */
public record NetAggregate(int samples, int online,
                           double latencySum, int latencyN,
                           double jitterSum, int jitterN,
                           long lossSum) {

    public static final NetAggregate EMPTY = new NetAggregate(0, 0, 0, 0, 0, 0, 0);

    /** net_samples tablosu uzerinde toplam ifadeleri (7 sutun, {@link #read} sirasiyla). */
    static final String COLUMNS = """
            COUNT(*), SUM(online),
            SUM(CASE WHEN latency_ms >= 0 THEN latency_ms END),
            COUNT(CASE WHEN latency_ms >= 0 THEN latency_ms END),
            SUM(CASE WHEN jitter_ms >= 0 THEN jitter_ms END),
            COUNT(CASE WHEN jitter_ms >= 0 THEN jitter_ms END),
            SUM(loss_pct)""";

    /** net_hourly tablosunun ayni sirayla sutunlari. */
    static final String HOURLY_COLUMNS =
            "samples, online_samples, latency_sum, latency_n, jitter_sum, jitter_n, loss_sum";

    static NetAggregate read(ResultSet rs, int col) throws SQLException {
        int samples = rs.getInt(col);
        if (samples == 0) return EMPTY;
        return new NetAggregate(samples, rs.getInt(col + 1),
                rs.getDouble(col + 2), rs.getInt(col + 3),
                rs.getDouble(col + 4), rs.getInt(col + 5),
                rs.getLong(col + 6));
    }

    public NetAggregate plus(NetAggregate o) {
        if (o.samples == 0) return this;
        if (samples == 0) return o;
        return new NetAggregate(samples + o.samples, online + o.online,
                latencySum + o.latencySum, latencyN + o.latencyN,
                jitterSum + o.jitterSum, jitterN + o.jitterN,
                lossSum + o.lossSum);
    }

    public NetSampleRepository.Summary toSummary() {
        if (samples == 0) return NetSampleRepository.Summary.empty();
        return new NetSampleRepository.Summary(
                latencyN == 0 ? -1 : latencySum / latencyN,
                jitterN == 0 ? -1 : jitterSum / jitterN,
                (double) lossSum / samples,
                online * 100.0 / samples,
                samples);
    }

    /** Grafik noktasi; ham kovalardaki gibi araliktaki tum olcumler cevrimiciyse "online". */
    public NetSample toPoint(long ts) {
        return new NetSample(ts, online == samples,
                latencyN == 0 ? -1 : latencySum / latencyN,
                jitterN == 0 ? -1 : jitterSum / jitterN,
                (int) Math.round((double) lossSum / samples), 0, 0, "");
    }
}
