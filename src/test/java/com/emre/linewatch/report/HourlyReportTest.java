package com.emre.linewatch.report;

import com.emre.linewatch.collector.LineSample;
import com.emre.linewatch.collector.NetSample;
import com.emre.linewatch.config.I18n;
import com.emre.linewatch.storage.Database;
import com.emre.linewatch.storage.EventRepository;
import com.emre.linewatch.storage.LineEvent;
import com.emre.linewatch.storage.NetSampleRepository;
import com.emre.linewatch.storage.RetentionJob;
import com.emre.linewatch.storage.SampleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 60 gunluk rapor: ayni olcumler bir veritabaninda tamamen ham, digerinde ilk 30 gun
 * yalnizca saatlik ozet olarak durur. Iki rapor (saat hassasiyeti disinda) ayni olmali.
 */
class HourlyReportTest {

    private static final long H = 3_600_000L;
    private static final long M = 60_000L;
    private static final long DAY = 24 * H;
    /** 2026-10-01 00:00 UTC */
    private static final long T0 = 1_790_812_800_000L;
    private static final long FROM = T0;
    private static final long TO = T0 + 60 * DAY - 1;
    private static final long NOW = T0 + 60 * DAY;
    /** Yalnizca ozetlerde kalan donemdeki kesinti; uygulama yeniden acilinca tekrar kaydedildi. */
    private static final long DOWN_START = T0 + 5 * DAY + 10 * H + 5 * M;
    private static final long DOWN_REPEAT = T0 + 5 * DAY + 14 * H;
    private static final long DOWN_END = T0 + 5 * DAY + 15 * H + 20 * M;
    /** CRC sayaci burada sifirlanir (modem yeniden basladi). */
    private static final long CRC_RESET = T0 + 10 * DAY;

    private final ReportOptions options = new ReportOptions("12345", 50, "TP-Link Archer VR400", 15 * 60, 6.0);
    private Database full;
    private Database mixed;

    @BeforeEach
    void setup() throws Exception {
        I18n.setLanguage("tr");
        full = fill(new Database(":memory:"));
        mixed = fill(new Database(":memory:"));
        new RetentionJob(mixed).run(NOW, 30, 365);
    }

    @AfterEach
    void teardown() {
        full.close();
        mixed.close();
        I18n.setLanguage(I18n.AUTO);
    }

    /** 15 dakikada bir DSL ve internet olcumu + kesinti olaylari. */
    private static Database fill(Database db) throws Exception {
        SampleRepository samples = new SampleRepository(db);
        NetSampleRepository net = new NetSampleRepository(db);
        int i = 0;
        for (long ts = T0; ts < NOW; ts += 15 * M, i++) {
            boolean up = ts < DOWN_START || ts >= DOWN_END;
            long crc = ts < CRC_RESET ? i : i - 900;
            samples.insert(new LineSample(ts, up ? "Up" : "Down", "VDSL2",
                    up ? 40000 + i % 1000 : 0, up ? 7400 : 0, up ? 50000 - i % 300 : 0, 8000,
                    up ? 100 + i % 50 : 0, up ? 80 + i % 7 : 0, 174, 44, 138, 67,
                    crc, 0, 0, 0, 0, 0, 0, 0));
            net.insert(up ? new NetSample(ts, true, 20 + i % 10, 1 + i % 3, i % 4 == 0 ? 25 : 0, 4, 4, "1.1.1.1")
                    : NetSample.offline(ts, 4));
        }
        event(db, DOWN_START, LineEvent.Type.LINK_DOWN);
        event(db, DOWN_REPEAT, LineEvent.Type.LINK_DOWN);
        return db;
    }

    private static void event(Database db, long ts, LineEvent.Type type) throws Exception {
        try (PreparedStatement ps = db.conn().prepareStatement(
                "INSERT INTO events(ts,type,detail,duration_sec) VALUES(?,?,?,?)")) {
            ps.setLong(1, ts);
            ps.setString(2, type.name());
            ps.setString(3, "d");
            ps.setLong(4, 0);
            ps.executeUpdate();
        }
    }

    private static ReportData build(Database db, long from, long to) throws Exception {
        return new ReportBuilder(new SampleRepository(db), new NetSampleRepository(db), new EventRepository(db),
                ZoneOffset.UTC).build(from, to, new ReportOptions("", 0, "", 15 * 60, 6.0), NOW);
    }

    @Test
    void mixedReportMatchesFullRawReport() throws Exception {
        ReportData a = build(full, FROM, TO);
        ReportData b = build(mixed, FROM, TO);

        assertFalse(a.usesHourly());
        assertTrue(b.usesHourly());
        assertEquals(T0 + 30 * DAY, b.hourlyUntil());

        var la = a.line();
        var lb = b.line();
        assertEquals(la.samples(), lb.samples());
        assertEquals(60 * 24 * 4, lb.samples());
        assertEquals(la.upSamples(), lb.upSamples());
        assertEquals(la.avgSnrDownDb(), lb.avgSnrDownDb(), 1e-9);
        assertEquals(la.minSnrDownDb(), lb.minSnrDownDb(), 1e-9);
        assertEquals(la.avgSnrUpDb(), lb.avgSnrUpDb(), 1e-9);
        assertEquals(la.avgSyncDown(), lb.avgSyncDown(), 1e-9);
        assertEquals(la.minSyncDown(), lb.minSyncDown());
        assertEquals(la.avgSyncUp(), lb.avgSyncUp(), 1e-9);
        assertEquals(la.avgMaxDown(), lb.avgMaxDown(), 1e-9);
        assertEquals(la.avgAttenDownDb(), lb.avgAttenDownDb(), 1e-9);
        assertEquals(la.modulation(), lb.modulation());

        assertEquals(a.lineCoveragePct(), b.lineCoveragePct(), 1e-9);
        assertEquals(100.0, b.lineCoveragePct(), 1e-9);
        assertEquals(a.netCoveragePct(), b.netCoveragePct(), 1e-9);

        assertEquals(a.net().samples(), b.net().samples());
        assertEquals(a.net().avgLatencyMs(), b.net().avgLatencyMs(), 1e-9);
        assertEquals(a.net().avgJitterMs(), b.net().avgJitterMs(), 1e-9);
        assertEquals(a.net().avgLossPct(), b.net().avgLossPct(), 1e-9);
        assertEquals(a.net().onlinePct(), b.net().onlinePct(), 1e-9);
    }

    @Test
    void crcIsCombinedAcrossHourlyAndRawParts() throws Exception {
        ReportData a = build(full, FROM, TO);
        ReportData b = build(mixed, FROM, TO);
        // Ham kismin ilk olcumune gelen tek adim (onceki olcum silindi) sayilamaz
        assertEquals(a.crcTotal() - 1, b.crcTotal());
        long byHour = 0;
        for (long v : b.crcByHour()) byHour += v;
        assertEquals(b.crcTotal(), byHour);
    }

    @Test
    void restartDuplicateInHourlyPeriodMergesAtHourPrecision() throws Exception {
        List<Outage> exact = build(full, FROM, TO).dslOutages();
        assertEquals(1, exact.size());
        assertEquals(T0 + 5 * DAY + 15 * H + 30 * M, exact.get(0).end(), "ilk bagli olcum 15:30");

        List<Outage> hourly = build(mixed, FROM, TO).dslOutages();
        assertEquals(1, hourly.size(), "tekrar kaydi ayri kesinti sayilmadi");
        Outage o = hourly.get(0);
        assertEquals(DOWN_START, o.start());
        assertTrue(o.approximate());
        // Saat hassasiyeti: bitis en gec 16:00, kopuk goruldugu son saat 15:00
        assertEquals(T0 + 5 * DAY + 16 * H, o.end());
        assertEquals((T0 + 5 * DAY + 15 * H - DOWN_START) / 1000, o.durationSec());
        assertTrue(o.durationSec() <= exact.get(0).durationSec(), "sure abartilmaz");
    }

    @Test
    void chartSeriesStayWithinPointBudgetAndKeepCrc() throws Exception {
        ReportData b = build(mixed, FROM, TO);
        List<LineSample> ls = b.lineSeries();
        List<NetSample> ns = b.netSeries();
        assertFalse(ls.isEmpty());
        assertTrue(ls.size() <= ReportBuilder.CHART_POINTS, "DSL nokta sayisi " + ls.size());
        assertTrue(ns.size() <= ReportBuilder.CHART_POINTS, "internet nokta sayisi " + ns.size());
        assertTrue(ls.size() > ReportBuilder.CHART_POINTS / 2, "iki kisim da cizilir");

        long crc = 0;
        for (int i = 1; i < ls.size(); i++) {
            assertTrue(ls.get(i).ts() > ls.get(i - 1).ts(), "noktalar zamana gore sirali");
            crc += Math.max(0, ls.get(i).crcDown() - ls.get(i - 1).crcDown());
        }
        crc += ls.get(0).crcDown();
        assertEquals(b.crcTotal(), crc, "grafikteki CRC artislari toplamla tutarli");
        for (int i = 1; i < ns.size(); i++) assertTrue(ns.get(i).ts() > ns.get(i - 1).ts());

        // Ozet noktalari ham veri ufkundan once, donem icinde
        assertTrue(ls.get(0).ts() >= FROM);
        assertTrue(ls.stream().anyMatch(s -> s.ts() < b.hourlyUntil()));
    }

    @Test
    void periodsInsideOnePartUseOnlyThatSource() throws Exception {
        ReportData raw = build(mixed, T0 + 40 * DAY, T0 + 50 * DAY);
        assertFalse(raw.usesHourly());
        assertEquals(build(full, T0 + 40 * DAY, T0 + 50 * DAY).crcTotal(), raw.crcTotal());

        ReportData old = build(mixed, T0 + 2 * DAY, T0 + 9 * DAY - 1);
        ReportData oldFull = build(full, T0 + 2 * DAY, T0 + 9 * DAY - 1);
        assertTrue(old.usesHourly());
        assertEquals(T0 + 9 * DAY - 1, old.hourlyUntil());
        assertEquals(oldFull.line().samples(), old.line().samples());
        assertEquals(oldFull.line().avgSnrDownDb(), old.line().avgSnrDownDb(), 1e-9);
        // Ozet, donemin ilk saatine onceki saatin son olcumunden gelen adimi da katar; ham hesap katamaz
        assertEquals(oldFull.crcTotal() + 1, old.crcTotal());
        assertEquals(oldFull.net().onlinePct(), old.net().onlinePct(), 1e-9);
    }

    @Test
    void htmlMentionsHourlySummaries() throws Exception {
        String html = HtmlReport.render(build(mixed, FROM, TO), ZoneOffset.UTC);
        assertTrue(html.contains("saatlik özetler kullanıldı"));
        assertTrue(html.contains("31.10.2026 00:00"));
        assertFalse(HtmlReport.render(build(full, FROM, TO), ZoneOffset.UTC).contains("saatlik özetler"));
    }
}
