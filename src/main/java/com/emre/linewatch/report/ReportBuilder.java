package com.emre.linewatch.report;

import com.emre.linewatch.collector.LineSample;
import com.emre.linewatch.collector.NetSample;
import com.emre.linewatch.storage.EventRepository;
import com.emre.linewatch.storage.LineEvent;
import com.emre.linewatch.storage.NetSampleRepository;
import com.emre.linewatch.storage.SampleRepository;

import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Veritabanindan bir zaman araligi icin {@link ReportData} toplar. */
public class ReportBuilder {

    /** Grafiklerdeki nokta sayisi: A4 genisliginde ayirt edilebilecek kadar. */
    static final int CHART_POINTS = 600;

    private final SampleRepository samples;
    private final NetSampleRepository netSamples;
    private final EventRepository events;
    private final ZoneId zone;

    public ReportBuilder(SampleRepository samples, NetSampleRepository netSamples, EventRepository events) {
        this(samples, netSamples, events, ZoneId.systemDefault());
    }

    /** Test icin: saat dagilimi sabit bir saat diliminde hesaplanabilsin. */
    ReportBuilder(SampleRepository samples, NetSampleRepository netSamples, EventRepository events, ZoneId zone) {
        this.samples = samples;
        this.netSamples = netSamples;
        this.events = events;
        this.zone = zone;
    }

    public ReportData build(long from, long to, ReportOptions options) throws SQLException {
        return build(from, to, options, System.currentTimeMillis());
    }

    ReportData build(long from, long to, ReportOptions options, long now) throws SQLException {
        if (to <= from) throw new IllegalArgumentException("to <= from");

        var line = samples.stats(from, to);
        var net = netSamples.summaryRange(from, to);

        List<LineEvent> eventList = events.findRange(from, to);
        List<Outage> dsl;
        List<Outage> netOutages;
        try {
            dsl = Outage.fromEvents(withPrevious(eventList, from, LineEvent.Type.LINK_DOWN, LineEvent.Type.LINK_UP),
                    Outage.Kind.DSL, from, to, new Outage.Timeline() {
                        @Override
                        public long firstUpAfter(long ts) { return sql(() -> samples.firstUpAfter(ts)); }

                        @Override
                        public long lastDownBetween(long a, long b) { return sql(() -> samples.lastDownBetween(a, b)); }

                        @Override
                        public long dataStart() { return sql(samples::firstTs); }
                    });
            netOutages = Outage.fromEvents(withPrevious(eventList, from, LineEvent.Type.NET_DOWN, LineEvent.Type.NET_UP),
                    Outage.Kind.NET, from, to, new Outage.Timeline() {
                        @Override
                        public long firstUpAfter(long ts) { return sql(() -> netSamples.firstOnlineAfter(ts)); }

                        @Override
                        public long lastDownBetween(long a, long b) { return sql(() -> netSamples.lastOfflineBetween(a, b)); }

                        @Override
                        public long dataStart() { return sql(netSamples::firstTs); }
                    });
        } catch (SqlFailure e) {
            throw e.cause;
        }

        long[] crcByHour = new long[24];
        long crcTotal = 0;
        long prev = -1;
        for (long[] point : samples.findCrcPoints(from, to)) {
            // Sayac geri gittiyse modem yeniden baslamis ya da hat yeniden senkron
            // olmustur; o adim sayilmaz (eksik sayim, fazla sayimdan iyidir).
            if (prev >= 0 && point[1] > prev) {
                long delta = point[1] - prev;
                crcTotal += delta;
                crcByHour[hourOf(point[0])] += delta;
            }
            prev = point[1];
        }

        Map<LineEvent.Type, Integer> counts = new EnumMap<>(LineEvent.Type.class);
        for (LineEvent e : eventList) counts.merge(e.type(), 1, Integer::sum);

        List<LineSample> lineSeries = line.samples() > 0
                ? samples.findRange(from, to, CHART_POINTS) : List.of();
        List<NetSample> netSeries = net.hasData()
                ? netSamples.findRange(from, to, CHART_POINTS) : List.of();

        long span = Math.max(1, Math.min(to, now) - from);
        return new ReportData(from, to, now, options, line, net,
                coverage(line.samples(), options.pollSeconds(), span),
                coverage(net.samples(), options.pollSeconds(), span),
                dsl, netOutages, crcTotal, crcByHour,
                startsByHour(dsl, from), startsByHour(netOutages, from),
                lineSeries, netSeries, counts);
    }

    /** Donemden once baslamis ve hala suren bir kesinti kaybolmasin diye onceki son olay basa eklenir. */
    private List<LineEvent> withPrevious(List<LineEvent> inRange, long from, LineEvent.Type down, LineEvent.Type up)
            throws SQLException {
        LineEvent previous = events.findLastBefore(from, down, up);
        if (previous == null) return inRange;
        List<LineEvent> out = new ArrayList<>(inRange.size() + 1);
        out.add(previous);
        out.addAll(inRange);
        return out;
    }

    private interface SqlCall {
        long run() throws SQLException;
    }

    /** Timeline arayuzu checked exception tasiyamadigi icin SQL hatasi sarilip build'de acilir. */
    private static long sql(SqlCall call) {
        try {
            return call.run();
        } catch (SQLException e) {
            throw new SqlFailure(e);
        }
    }

    private static final class SqlFailure extends RuntimeException {
        final SQLException cause;

        SqlFailure(SQLException cause) {
            super(cause);
            this.cause = cause;
        }
    }

    /**
     * Olcumlerin araligin yuzde kacini kapladigi. Bilgisayar kapaliyken olcum
     * yapilamaz; dusuk kapsam, raporun kesintileri eksik gosterebilecegi anlamina gelir.
     */
    static double coverage(int sampleCount, int pollSeconds, long spanMs) {
        if (spanMs <= 0) return 0;
        return Math.min(100.0, 100.0 * sampleCount * pollSeconds * 1000.0 / spanMs);
    }

    /** Aralik basindan once baslamis kesintiler saat dagilimina katilmaz (baslangici aralik disinda). */
    private int[] startsByHour(List<Outage> outages, long from) {
        int[] out = new int[24];
        for (Outage o : outages) {
            if (!o.startedBefore() && o.start() >= from) out[hourOf(o.start())]++;
        }
        return out;
    }

    private int hourOf(long ts) {
        return Instant.ofEpochMilli(ts).atZone(zone).getHour();
    }
}
