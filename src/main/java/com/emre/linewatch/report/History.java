package com.emre.linewatch.report;

import com.emre.linewatch.collector.LineSample;
import com.emre.linewatch.collector.NetSample;
import com.emre.linewatch.storage.HourlyRepository;
import com.emre.linewatch.storage.HourlyRepository.LineHour;
import com.emre.linewatch.storage.HourlyRepository.NetHour;
import com.emre.linewatch.storage.LineAggregate;
import com.emre.linewatch.storage.NetAggregate;
import com.emre.linewatch.storage.NetSampleRepository;
import com.emre.linewatch.storage.SampleRepository;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

import static com.emre.linewatch.storage.HourlyRepository.HOUR;

/**
 * Bir rapor donemi icin ham olcumleri ve saatlik ozetleri birlestirir.
 *
 * <p>Ham veri ufku B, saklanan ilk ham olcumun saat basidir. B'den onceki saatler
 * saatlik ozetlerden, B ve sonrasi ham olcumlerden gelir; ham olcum B'den once
 * olmadigi icin iki kaynak hic cakismaz. Donem basini iceren saat tamamen sayilir
 * (saat hassasiyeti). B donem basina esit ya da once ise rapor eskisi gibi
 * yalnizca ham olcumlerden olusur.
 */
final class History {

    private final SampleRepository samples;
    private final NetSampleRepository net;
    private final HourlyRepository hourly;
    private final long from;
    private final long to;
    private final long lineHorizon;
    private final long netHorizon;
    final List<LineHour> lineHours;
    final List<NetHour> netHours;

    History(SampleRepository samples, NetSampleRepository net, HourlyRepository hourly, long from, long to)
            throws SQLException {
        this.samples = samples;
        this.net = net;
        this.hourly = hourly;
        this.from = from;
        this.to = to;
        lineHorizon = horizon(samples.firstTs());
        netHorizon = horizon(net.firstTs());
        lineHours = hourly.lineHours(from - HOUR + 1, Math.min(lineHorizon, to + 1));
        netHours = hourly.netHours(from - HOUR + 1, Math.min(netHorizon, to + 1));
    }

    private static long horizon(long firstTs) {
        return firstTs == Long.MAX_VALUE ? Long.MAX_VALUE : HourlyRepository.floorHour(firstTs);
    }

    /** Bu andan onceki kisim saatlik ozetlerden geldi; ozet kullanilmadiysa {@code from}. */
    long hourlyUntil() {
        long until = from;
        if (!lineHours.isEmpty()) until = Math.max(until, Math.min(lineHorizon, to));
        if (!netHours.isEmpty()) until = Math.max(until, Math.min(netHorizon, to));
        return until;
    }

    SampleRepository.LineStats lineStats() throws SQLException {
        LineAggregate total = samples.aggregate(from, to);
        for (LineHour h : lineHours) total = total.plus(h.agg());
        return total.toStats();
    }

    NetSampleRepository.Summary netSummary() throws SQLException {
        NetAggregate total = net.aggregate(from, to);
        for (NetHour h : netHours) total = total.plus(h.agg());
        return total.toSummary();
    }

    // ---------- grafik serileri ----------

    /**
     * DSL grafigi: ozet saatleri dilimlere toplanip dilim ortasina yerlestirilir, ardindan
     * ham kovalar gelir; toplam nokta sayisi {@code maxPoints}'i asmaz. Ozet kullanilan
     * raporda CRC alani, HtmlReport'un ardisik farklari dogru hesaplayabilmesi icin
     * yapay bir kumulatif toplam olur (sayac sifirlanmalari zaten ayiklanmis).
     */
    List<LineSample> lineSeries(int maxPoints) throws SQLException {
        if (lineHours.isEmpty()) return samples.findRange(from, to, maxPoints);
        long rawFrom = Math.max(from, lineHorizon);
        List<List<LineHour>> slots = slots(lineHours, LineHour::hour, rawFrom, maxPoints);
        List<LineSample> out = new ArrayList<>();
        long crc = 0;
        for (List<LineHour> slot : slots) {
            LineAggregate agg = LineAggregate.EMPTY;
            for (LineHour h : slot) {
                agg = agg.plus(h.agg());
                crc += h.crcInc();
            }
            out.add(agg.toPoint(center(slot.get(0).hour(), slot.get(slot.size() - 1).hour(), rawFrom), crc));
        }
        if (rawFrom <= to) {
            List<LineSample> raw = samples.findRange(rawFrom, to, Math.max(1, maxPoints - out.size() - 1));
            long prev = raw.isEmpty() ? -1 : samples.firstCrcSince(rawFrom);
            for (LineSample s : raw) {
                if (prev >= 0 && s.crcDown() > prev) crc += s.crcDown() - prev;
                prev = s.crcDown();
                out.add(withCrc(s, crc));
            }
        }
        return out;
    }

    List<NetSample> netSeries(int maxPoints) throws SQLException {
        if (netHours.isEmpty()) return net.findRange(from, to, maxPoints);
        long rawFrom = Math.max(from, netHorizon);
        List<NetSample> out = new ArrayList<>();
        for (List<NetHour> slot : slots(netHours, NetHour::hour, rawFrom, maxPoints)) {
            NetAggregate agg = NetAggregate.EMPTY;
            for (NetHour h : slot) agg = agg.plus(h.agg());
            out.add(agg.toPoint(center(slot.get(0).hour(), slot.get(slot.size() - 1).hour(), rawFrom)));
        }
        if (rawFrom <= to) out.addAll(net.findRange(rawFrom, to, Math.max(1, maxPoints - out.size() - 1)));
        return out;
    }

    /**
     * Saatleri g saatlik dilimlere ayirir. g, ham kisimla ayni kova genisligine yakin
     * secilir; dilim sayisi ozet kisminin nokta payini asarsa g buyutulur.
     */
    private <T> List<List<T>> slots(List<T> hours, ToLongFunction<T> hourOf, long rawFrom, int maxPoints) {
        long bucket = Math.max(1, (to - from) / Math.max(1, maxPoints));
        int rawPoints = rawFrom <= to ? (int) Math.min(maxPoints, (to - rawFrom) / bucket + 1) : 0;
        int limit = Math.max(1, maxPoints - rawPoints - 1);
        long origin = hourOf.applyAsLong(hours.get(0));
        for (long g = Math.max(1, Math.ceilDiv(bucket, HOUR)); ; g++) {
            List<List<T>> out = new ArrayList<>();
            long current = Long.MIN_VALUE;
            for (T h : hours) {
                long idx = (hourOf.applyAsLong(h) - origin) / (g * HOUR);
                if (idx != current) {
                    out.add(new ArrayList<>());
                    current = idx;
                }
                out.get(out.size() - 1).add(h);
            }
            if (out.size() <= limit) return out;
        }
    }

    /** Dilimin ortasi; donem ve ham veri ufku disina tasmaz (siralama korunur). */
    private long center(long firstHour, long lastHour, long rawFrom) {
        long mid = (firstHour + lastHour + HOUR) / 2;
        return Math.max(from, Math.min(mid, Math.min(rawFrom, to + 1) - 1));
    }

    private static LineSample withCrc(LineSample s, long crc) {
        return new LineSample(s.ts(), s.status(), s.modulation(), s.syncDown(), s.syncUp(),
                s.maxRateDown(), s.maxRateUp(), s.snrDown(), s.snrUp(), s.attenDown(), s.attenUp(),
                s.powerDown(), s.powerUp(), crc, s.crcUp(), s.fecDown(), s.fecUp(),
                s.hecDown(), s.hecUp(), s.erroredSecs(), s.severelyErroredSecs());
    }

    // ---------- kesinti zaman cizelgeleri ----------

    /**
     * Ham olcumlerle, ham veri olmayan donemde saatlik ozetlerle calisan zaman cizelgesi.
     * Ozet kisminda saat hassasiyetindedir: "ilk bagli olcum" o saatin sonu olarak
     * (en gec), "son kopuk olcum" o saatin basi olarak (en erken) verilir; boylece
     * kesinti suresi abartilmaz. ts'nin kendi saati sayilmaz (once mi sonra mi belli degil).
     */
    Outage.Timeline lineTimeline() {
        return timeline(lineHorizon,
                ts -> samples.firstUpAfter(ts), (a, b) -> samples.lastDownBetween(a, b),
                ts -> hourly.firstUpHourAfter(ts, lineHorizon), (a, b) -> hourly.lastDownHourBetween(a, b),
                () -> Math.min(samples.firstTs(), hourly.firstLineHour()));
    }

    Outage.Timeline netTimeline() {
        return timeline(netHorizon,
                ts -> net.firstOnlineAfter(ts), (a, b) -> net.lastOfflineBetween(a, b),
                ts -> hourly.firstOnlineHourAfter(ts, netHorizon), (a, b) -> hourly.lastOfflineHourBetween(a, b),
                () -> Math.min(net.firstTs(), hourly.firstNetHour()));
    }

    private interface At {
        long get(long ts) throws SQLException;
    }

    private interface Between {
        long get(long a, long b) throws SQLException;
    }

    private interface Value {
        long get() throws SQLException;
    }

    private static Outage.Timeline timeline(long horizon, At rawUp, Between rawDown, At hourUp, Between hourDown,
                                            Value start) {
        return new Outage.Timeline() {
            @Override
            public long firstUpAfter(long ts) {
                if (ts < horizon) {
                    long h = ReportBuilder.sql(() -> hourUp.get(ts));
                    if (h >= 0) return h + HOUR;
                }
                return ReportBuilder.sql(() -> rawUp.get(ts));
            }

            @Override
            public long lastDownBetween(long a, long b) {
                long raw = ReportBuilder.sql(() -> rawDown.get(a, b));
                if (raw >= 0 || a >= horizon) return raw;
                return ReportBuilder.sql(() -> hourDown.get(a, Math.min(b, horizon)));
            }

            @Override
            public long dataStart() {
                return ReportBuilder.sql(start::get);
            }
        };
    }
}
