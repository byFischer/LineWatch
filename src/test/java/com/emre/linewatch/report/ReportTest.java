package com.emre.linewatch.report;

import com.emre.linewatch.collector.LineSample;
import com.emre.linewatch.collector.NetSample;
import com.emre.linewatch.config.I18n;
import com.emre.linewatch.storage.Database;
import com.emre.linewatch.storage.EventRepository;
import com.emre.linewatch.storage.LineEvent;
import com.emre.linewatch.storage.NetSampleRepository;
import com.emre.linewatch.storage.SampleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Gercek veritabani yerine bellek ici SQLite; saat dilimi UTC'ye sabit. */
class ReportTest {

    private static final long H = 3_600_000L;
    /** 2026-10-01 00:00 UTC */
    private static final long T0 = 1_790_812_800_000L;

    private Database db;
    private SampleRepository samples;
    private NetSampleRepository net;
    private EventRepository events;
    private ReportBuilder builder;
    private final ReportOptions options = new ReportOptions("12345", 50, "TP-Link Archer VR400", 15, 6.0);

    @BeforeEach
    void setup() throws Exception {
        I18n.setLanguage("tr");
        db = new Database(":memory:");
        samples = new SampleRepository(db);
        net = new NetSampleRepository(db);
        events = new EventRepository(db);
        builder = new ReportBuilder(samples, net, events, ZoneOffset.UTC);
    }

    @AfterEach
    void teardown() {
        db.close();
        I18n.setLanguage(I18n.AUTO);
    }

    private void event(long ts, LineEvent.Type type, String detail, long durationSec) throws Exception {
        try (PreparedStatement ps = db.conn().prepareStatement(
                "INSERT INTO events(ts,type,detail,duration_sec) VALUES(?,?,?,?)")) {
            ps.setLong(1, ts);
            ps.setString(2, type.name());
            ps.setString(3, detail);
            ps.setLong(4, durationSec);
            ps.executeUpdate();
        }
    }

    private static LineSample line(long ts, String status, int snr, int sync, long crc) {
        return new LineSample(ts, status, "VDSL2", sync, 7460, 39611, 7461,
                snr, 81, 174, 44, 138, 67, crc, 0, 0, 0, 0, 0, 0, 0);
    }

    private static LineEvent ev(long ts, LineEvent.Type type, long dur) {
        return new LineEvent(0, ts, type, "d" + ts, dur);
    }

    // ---------- kesinti eslestirme ----------

    @Test
    void downThenUpBecomesOneOutage() {
        var list = Outage.fromEvents(List.of(
                ev(T0 + H, LineEvent.Type.LINK_DOWN, 0),
                ev(T0 + H + 90_000, LineEvent.Type.LINK_UP, 90)), Outage.Kind.DSL, T0, T0 + 24 * H, Outage.Timeline.NONE);
        assertEquals(1, list.size());
        Outage o = list.get(0);
        assertEquals(T0 + H, o.start());
        assertEquals(90, o.durationSec());
        assertEquals("d" + (T0 + H), o.detail(), "ayrinti kopma olayindan gelmeli");
        assertFalse(o.startedBefore());
    }

    @Test
    void upWithoutDownUsesRecordedDurationAndClipsToPeriod() {
        // Kesinti donem basindan 10 dk once baslamis, 5 dk sonra bitmis
        var list = Outage.fromEvents(List.of(ev(T0 + 5 * 60_000, LineEvent.Type.NET_UP, 15 * 60)),
                Outage.Kind.NET, T0, T0 + 24 * H, Outage.Timeline.NONE);
        assertEquals(1, list.size());
        assertEquals(T0, list.get(0).start());
        assertEquals(5 * 60, list.get(0).durationSec(), "yalnizca donem icindeki kisim sayilmali");
        assertTrue(list.get(0).startedBefore());
    }

    @Test
    void repeatedDownsWithoutStoredSamplesStaySeparateAndUnknown() {
        // Ham olcum yok (silinmis): tekrar kaydi oldugu dogrulanamaz, birlestirilmez
        var list = Outage.fromEvents(List.of(
                ev(T0 + H, LineEvent.Type.LINK_DOWN, 0),
                ev(T0 + 2 * H, LineEvent.Type.LINK_DOWN, 0)), Outage.Kind.DSL, T0, T0 + 24 * H, Outage.Timeline.NONE);
        assertEquals(2, list.size());
        assertTrue(list.stream().noneMatch(Outage::durationKnown));
        assertTrue(list.stream().noneMatch(Outage::approximate));
    }

    @Test
    void outageBeforeSampleHorizonIsNotStretched() throws Exception {
        // Kopma kaydi ham olcumlerin silindigi doneme ait; sonraki olcumler hala kopuk
        event(T0 + H, LineEvent.Type.LINK_DOWN, "eski", 0);
        samples.insert(line(T0 + 5 * H, "NoSignal", 0, 0, 0));
        samples.insert(line(T0 + 6 * H, "Up", 117, 24574, 0));
        ReportData r = builder.build(T0, T0 + 24 * H - 1, options, T0 + 24 * H);
        assertEquals(1, r.dslOutages().size());
        assertFalse(r.dslOutages().get(0).durationKnown(), "sure olcum olmayan doneme uzatilmamali");
    }

    // ---------- yeniden baslatma senaryolari (gercek veride goruldu) ----------

    @Test
    void restartDuplicatesMergeIntoOneApproximateOutage() throws Exception {
        // 01:00 koptu, uygulama 02:00'de kapandi, 05:00'te acildi (hala kopuk), 06:00'da hat geldi
        event(T0 + H, LineEvent.Type.LINK_DOWN, "NoSignal", 0);
        samples.insert(line(T0 + H, "NoSignal", 0, 0, 0));
        samples.insert(line(T0 + 2 * H, "NoSignal", 0, 0, 0));
        event(T0 + 5 * H, LineEvent.Type.LINK_DOWN, "NoSignal", 0);
        samples.insert(line(T0 + 5 * H, "NoSignal", 0, 0, 0));
        samples.insert(line(T0 + 6 * H - 15_000, "NoSignal", 0, 0, 0));
        samples.insert(line(T0 + 6 * H, "Up", 117, 24574, 0));

        ReportData r = builder.build(T0, T0 + 24 * H - 1, options, T0 + 24 * H);
        assertEquals(1, r.dslOutages().size(), "tekrar kaydi ayri kesinti sayilmamali");
        Outage o = r.dslOutages().get(0);
        assertTrue(o.approximate());
        assertEquals(T0 + H, o.start());
        assertEquals(T0 + 6 * H, o.end(), "bitis ilk Up olcumu");
        assertEquals(5 * 3600 - 15, o.durationSec(), "sure son kopuk olcume kadar");
        assertEquals(1, r.dslDropsByHour()[1]);
        assertEquals(0, r.dslDropsByHour()[5]);
    }

    @Test
    void downsSeparatedByUpSampleStayTwoOutages() throws Exception {
        event(T0 + H, LineEvent.Type.LINK_DOWN, "a", 0);
        samples.insert(line(T0 + H, "NoSignal", 0, 0, 0));
        samples.insert(line(T0 + H + 60_000, "NoSignal", 0, 0, 0));
        samples.insert(line(T0 + 2 * H, "Up", 117, 24574, 0)); // uygulama kapaliyken hat gelmis
        event(T0 + 3 * H, LineEvent.Type.LINK_DOWN, "b", 0);
        event(T0 + 3 * H + 30_000, LineEvent.Type.LINK_UP, "", 30);

        ReportData r = builder.build(T0, T0 + 24 * H - 1, options, T0 + 24 * H);
        assertEquals(2, r.dslOutages().size());
        assertEquals(60, r.dslOutages().get(0).durationSec());
        assertTrue(r.dslOutages().get(0).approximate());
        assertFalse(r.dslOutages().get(1).approximate());
    }

    @Test
    void outageStartedBeforePeriodIsIncluded() throws Exception {
        event(T0 - 10 * H, LineEvent.Type.LINK_DOWN, "eski", 0);
        samples.insert(line(T0 - 10 * H, "NoSignal", 0, 0, 0));
        samples.insert(line(T0 + 2 * H, "NoSignal", 0, 0, 0));
        samples.insert(line(T0 + 3 * H, "Up", 117, 24574, 0));

        ReportData r = builder.build(T0, T0 + 24 * H - 1, options, T0 + 24 * H);
        assertEquals(1, r.dslOutages().size());
        Outage o = r.dslOutages().get(0);
        assertTrue(o.startedBefore());
        assertEquals(T0, o.start(), "donem basina kirpilmali");
        assertEquals(2 * 3600, o.durationSec());
        assertEquals(0, r.eventCounts().getOrDefault(LineEvent.Type.LINK_DOWN, 0),
                "donem disindaki olay sayilara katilmamali");
    }

    @Test
    void outageEndedBeforePeriodIsIgnored() throws Exception {
        event(T0 - 10 * H, LineEvent.Type.LINK_DOWN, "eski", 0);
        samples.insert(line(T0 - 10 * H, "NoSignal", 0, 0, 0));
        samples.insert(line(T0 - 9 * H, "Up", 117, 24574, 0));
        ReportData r = builder.build(T0, T0 + 24 * H - 1, options, T0 + 24 * H);
        assertTrue(r.dslOutages().isEmpty());
    }

    @Test
    void recoveryEventBeforePeriodIsNotAnOutage() throws Exception {
        // Donemden onceki son internet olayi "geri geldi": donemde kesinti yok
        event(T0 - 20 * H, LineEvent.Type.NET_UP, "", 300);
        ReportData r = builder.build(T0, T0 + 24 * H - 1, options, T0 + 24 * H);
        assertTrue(r.netOutages().isEmpty());
    }

    @Test
    void invalidSnrReadingIsIgnored() throws Exception {
        samples.insert(line(T0 + 1000, "Up", 117, 24574, 0));
        samples.insert(line(T0 + 2000, "Up", -510, 24574, 0)); // modemin -51 dB isaret degeri
        ReportData r = builder.build(T0, T0 + H, options, T0 + H);
        assertEquals(11.7, r.line().minSnrDownDb(), 1e-9);
        assertEquals(11.7, r.line().avgSnrDownDb(), 1e-9);
    }

    @Test
    void dslAndNetEventsAreSeparated() {
        var events = List.of(
                ev(T0, LineEvent.Type.NET_DOWN, 0),
                ev(T0 + 1000, LineEvent.Type.LINK_DOWN, 0),
                ev(T0 + 61_000, LineEvent.Type.LINK_UP, 60));
        assertEquals(1, Outage.fromEvents(events, Outage.Kind.DSL, T0, T0 + H, Outage.Timeline.NONE).size());
        var netList = Outage.fromEvents(events, Outage.Kind.NET, T0, T0 + H, Outage.Timeline.NONE);
        assertEquals(1, netList.size());
        assertFalse(netList.get(0).endKnown(), "NET_UP gelmediyse bitis bilinmiyor");
    }

    // ---------- rapor verisi ----------

    @Test
    void crcTotalIgnoresCounterResets() throws Exception {
        samples.insert(line(T0 + 1000, "Up", 117, 24574, 1000));
        samples.insert(line(T0 + 2000, "Up", 117, 24574, 1300));       // +300 (saat 00)
        samples.insert(line(T0 + 3 * H, "Up", 117, 24574, 10));        // sifirlandi: sayilmaz
        samples.insert(line(T0 + 3 * H + 1000, "Up", 117, 24574, 60)); // +50 (saat 03)

        ReportData r = builder.build(T0, T0 + 24 * H - 1, options, T0 + 24 * H);
        assertEquals(350, r.crcTotal());
        assertEquals(300, r.crcByHour()[0]);
        assertEquals(50, r.crcByHour()[3]);
    }

    @Test
    void lineStatsUseOnlyUpSamples() throws Exception {
        samples.insert(line(T0 + 1000, "Up", 120, 24000, 0));
        samples.insert(line(T0 + 2000, "Up", 50, 22000, 0));
        samples.insert(line(T0 + 3000, "Down", 0, 0, 0));

        ReportData r = builder.build(T0, T0 + H, options, T0 + H);
        assertEquals(3, r.line().samples());
        assertEquals(2, r.line().upSamples());
        assertEquals(8.5, r.line().avgSnrDownDb(), 1e-9);
        assertEquals(5.0, r.line().minSnrDownDb(), 1e-9);
        assertEquals(23000, r.line().avgSyncDown(), 1e-9);
    }

    @Test
    void coverageIsCappedAndProportional() {
        assertEquals(50.0, ReportBuilder.coverage(120, 15, H), 1e-9);  // 120 x 15 sn = 30 dk
        assertEquals(100.0, ReportBuilder.coverage(1000, 15, H), 1e-9);
        assertEquals(0.0, ReportBuilder.coverage(10, 15, 0), 1e-9);
    }

    @Test
    void coverageUsesNowWhenPeriodExtendsIntoFuture() throws Exception {
        for (int i = 0; i < 240; i++) net.insert(new NetSample(T0 + i * 15_000L, true, 20, 2, 0, 3, 3, "x"));
        // Donem 24 saat ama simdi 1. saatte: kapsam 1 saate gore hesaplanir
        ReportData r = builder.build(T0, T0 + 24 * H, options, T0 + H);
        assertEquals(100.0, r.netCoveragePct(), 1e-9);
    }

    @Test
    void dropsByHourCountsOnlyOutagesStartingInPeriod() throws Exception {
        event(T0 + 20 * H, LineEvent.Type.LINK_DOWN, "kopma", 0);
        event(T0 + 20 * H + 30_000, LineEvent.Type.LINK_UP, "geldi", 30);
        event(T0 + 5 * 60_000, LineEvent.Type.LINK_UP, "geldi", 600); // donemden once baslamis

        ReportData r = builder.build(T0, T0 + 24 * H - 1, options, T0 + 24 * H);
        assertEquals(2, r.dslOutages().size());
        assertEquals(1, r.dslDropsByHour()[20]);
        assertEquals(0, r.dslDropsByHour()[0]);
    }

    // ---------- HTML ----------

    @Test
    void htmlContainsFiguresAndEscapesDetails() throws Exception {
        samples.insert(line(T0 + 1000, "Up", 117, 24574, 0));
        samples.insert(line(T0 + 2000, "Up", 117, 24574, 10));
        net.insert(new NetSample(T0 + 1000, true, 23.4, 1.5, 0, 3, 3, "Cloudflare"));
        event(T0 + H, LineEvent.Type.LINK_DOWN, "<script>alert(1)</script>", 0);
        event(T0 + H + 125_000, LineEvent.Type.LINK_UP, "geldi", 125);

        String html = HtmlReport.render(builder.build(T0, T0 + 24 * H - 1, options, T0 + 24 * H), ZoneOffset.UTC);

        assertTrue(html.startsWith("<!doctype html>"));
        assertTrue(html.contains("Hat ve İnternet Kalite Raporu"));
        assertTrue(html.contains("12345"), "abone numarasi");
        assertTrue(html.contains("2 dk 5 sn"), "kesinti suresi");
        assertTrue(html.contains("&lt;script&gt;"), "ayrinti kacislanmali");
        assertFalse(html.contains("<script>alert"), "ham HTML sizmamali");
        assertFalse(html.contains("!report."), "eksik ceviri anahtari");
        assertFalse(html.contains("!fmt."), "eksik ceviri anahtari");
        assertTrue(html.contains("<svg"), "grafikler gomulu olmali");
    }

    @Test
    void netOnlyReportSkipsDslSections() throws Exception {
        net.insert(new NetSample(T0 + 1000, true, 23.4, 1.5, 0, 3, 3, "Cloudflare"));
        String html = HtmlReport.render(builder.build(T0, T0 + H, options, T0 + H), ZoneOffset.UTC);
        assertTrue(html.contains(I18n.t("report.deviceNone")));
        assertFalse(html.contains(I18n.t("report.chartSnr")));
        assertTrue(html.contains(I18n.t("report.chartLatency")));
    }

    @Test
    void emptyPeriodStillRenders() throws Exception {
        String html = HtmlReport.render(builder.build(T0, T0 + H, options, T0 + H), ZoneOffset.UTC);
        assertTrue(html.contains(I18n.t("report.fNoData")));
        assertFalse(html.contains("!report."));
    }

    @Test
    void englishReportHasNoMissingKeys() throws Exception {
        I18n.setLanguage("en");
        samples.insert(line(T0 + 1000, "Up", 50, 24574, 0));
        net.insert(new NetSample(T0 + 1000, false, -1, -1, 100, 3, 0, ""));
        event(T0 + 2000, LineEvent.Type.LINK_DOWN, "x", 0);
        event(T0 + 3000, LineEvent.Type.CRC_BURST, "x", 0);
        String html = HtmlReport.render(builder.build(T0, T0 + H, options, T0 + H), ZoneOffset.UTC);
        assertTrue(html.contains("Line and Internet Quality Report"));
        assertFalse(html.contains("!report."));
        assertFalse(html.contains("!fmt."));
    }

    @Test
    void concentrationWindowWrapsAroundMidnight() {
        double[] byHour = new double[24];
        byHour[22] = 3;
        byHour[23] = 2;
        byHour[0] = 2;
        byHour[12] = 1;
        var w = HtmlReport.window(byHour, true);
        assertTrue(w.isPresent());
        assertTrue(w.get().contains("22:00–02:00"), w.get());
        assertTrue(HtmlReport.window(new double[24], true).isEmpty());
    }

    @Test
    void percentKeepsPrecisionNearHundred() {
        assertEquals("%99,96", HtmlReport.pct(99.96));
        assertEquals("%99,99", HtmlReport.pct(99.999));
        assertEquals("%100", HtmlReport.pct(100));
        assertEquals("%5", HtmlReport.pct(5.0));
        assertEquals("%42", HtmlReport.pct(42.4));
    }

    // ---------- CSV ----------

    @Test
    void csvConvertsUnitsAndQuotesText(@TempDir Path dir) throws Exception {
        samples.insert(line(T0, "Up", 117, 24574, 5));
        net.insert(new NetSample(T0, false, -1, -1, 100, 3, 0, ""));
        event(T0, LineEvent.Type.LINK_DOWN, "SNR 4,2 dB, \"kopma\"", 0);

        List<Path> files = new CsvExporter(db, ZoneOffset.UTC).export(T0, T0 + H, dir, "r");
        assertEquals(3, files.size());

        List<String> dsl = Files.readAllLines(dir.resolve("r-dsl.csv"), StandardCharsets.UTF_8);
        assertTrue(dsl.get(0).startsWith("﻿time,ts_ms,status"), dsl.get(0));
        assertTrue(dsl.get(0).contains("snr_down_db"));
        assertTrue(dsl.get(1).startsWith("2026-10-01 00:00:00," + T0 + ",Up,VDSL2,24574"), dsl.get(1));
        assertTrue(dsl.get(1).contains(",11.7,"), "SNR dB'ye cevrilmeli: " + dsl.get(1));

        List<String> netRows = Files.readAllLines(dir.resolve("r-internet.csv"), StandardCharsets.UTF_8);
        assertTrue(netRows.get(1).contains(",0,,,100,"), "olculemeyen gecikme bos olmali: " + netRows.get(1));

        List<String> ev = Files.readAllLines(dir.resolve("r-events.csv"), StandardCharsets.UTF_8);
        assertTrue(ev.get(1).endsWith("\"SNR 4,2 dB, \"\"kopma\"\"\""), ev.get(1));
    }

    @Test
    void csvSkipsEmptyTables(@TempDir Path dir) throws Exception {
        net.insert(new NetSample(T0, true, 20, 1, 0, 3, 3, "x"));
        List<Path> files = new CsvExporter(db, ZoneOffset.UTC).export(T0, T0 + H, dir, "r");
        assertEquals(List.of(dir.resolve("r-internet.csv")), files);
    }
}
