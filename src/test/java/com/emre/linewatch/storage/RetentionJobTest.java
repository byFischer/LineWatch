package com.emre.linewatch.storage;

import com.emre.linewatch.collector.LineSample;
import com.emre.linewatch.collector.NetSample;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Sema surumleri ve saatlik ozet / saklama isi; bellek ici SQLite. */
class RetentionJobTest {

    private static final long H = HourlyRepository.HOUR;
    private static final long M = 60_000L;
    private static final long DAY = 24 * H;
    /** 2026-10-01 00:00 UTC */
    private static final long T0 = 1_790_812_800_000L;

    private Database db;
    private SampleRepository samples;
    private NetSampleRepository net;
    private HourlyRepository hourly;
    private RetentionJob job;

    @BeforeEach
    void setup() throws Exception {
        db = new Database(":memory:");
        samples = new SampleRepository(db);
        net = new NetSampleRepository(db);
        hourly = new HourlyRepository(db);
        job = new RetentionJob(db);
    }

    @AfterEach
    void teardown() {
        db.close();
    }

    private static LineSample line(long ts, String status, int snr, int sync, long crc) {
        return new LineSample(ts, status, "VDSL2", sync, 7460, 39611, 7461,
                snr, 81, 174, 44, 138, 67, crc, 0, 0, 0, 0, 0, 0, 0);
    }

    private static NetSample netSample(long ts, boolean online, double latency, double jitter, int loss) {
        return new NetSample(ts, online, latency, jitter, loss, 4, online ? 4 : 0, "1.1.1.1");
    }

    private static int count(Database db, String sql) throws Exception {
        try (Statement st = db.conn().createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    // ---------- sema surumleri ----------

    @Test
    void freshDatabaseIsAtLatestVersion() throws Exception {
        assertEquals(Database.SCHEMA_VERSION, db.schemaVersion());
        assertEquals(2, Database.SCHEMA_VERSION);
        assertEquals(0, count(db, "SELECT COUNT(*) FROM samples_hourly"));
        assertEquals(0, count(db, "SELECT COUNT(*) FROM net_hourly"));
    }

    @Test
    void oldDatabaseWithoutVersionIsMigratedAndKeepsData(@TempDir Path dir) throws Exception {
        String file = dir.resolve("old.db").toString();
        // Surum numarasi olmayan eski veritabani: v1 tablolari var, user_version 0
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE samples(ts INTEGER PRIMARY KEY, status TEXT, modulation TEXT,"
                    + " sync_down INTEGER, sync_up INTEGER, max_down INTEGER, max_up INTEGER,"
                    + " snr_down INTEGER, snr_up INTEGER, atten_down INTEGER, atten_up INTEGER,"
                    + " power_down INTEGER, power_up INTEGER, crc_down INTEGER, crc_up INTEGER,"
                    + " fec_down INTEGER, fec_up INTEGER, hec_down INTEGER, hec_up INTEGER,"
                    + " errored_secs INTEGER, sev_errored_secs INTEGER)");
            st.execute("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL,"
                    + " type TEXT NOT NULL, detail TEXT, duration_sec INTEGER)");
            st.execute("CREATE TABLE net_samples(ts INTEGER PRIMARY KEY, online INTEGER NOT NULL,"
                    + " latency_ms REAL, jitter_ms REAL, loss_pct INTEGER, sent INTEGER, received INTEGER, target TEXT)");
            st.execute("INSERT INTO samples(ts, status, snr_down, crc_down) VALUES(" + T0 + ", 'Up', 120, 5)");
            st.execute("INSERT INTO events(ts, type) VALUES(" + T0 + ", 'LINK_DOWN')");
        }

        Database migrated = new Database(file);
        try {
            assertEquals(2, migrated.schemaVersion());
            assertEquals(1, count(migrated, "SELECT COUNT(*) FROM samples"));
            assertEquals(1, count(migrated, "SELECT COUNT(*) FROM events"));
            assertEquals(0, count(migrated, "SELECT COUNT(*) FROM samples_hourly"));
            assertEquals(1, count(migrated,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'idx_events_ts'"));
        } finally {
            migrated.close();
        }

        // Ikinci acilis hicbir adimi tekrar calistirmaz, veri yerinde kalir
        Database reopened = new Database(file);
        try {
            assertEquals(2, reopened.schemaVersion());
            assertEquals(1, count(reopened, "SELECT COUNT(*) FROM samples"));
        } finally {
            reopened.close();
        }
    }

    @Test
    void versionOneDatabaseGetsHourlyTables(@TempDir Path dir) throws Exception {
        String file = dir.resolve("v1.db").toString();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE samples(ts INTEGER PRIMARY KEY, status TEXT)");
            st.execute("PRAGMA user_version = 1");
        }
        Database migrated = new Database(file);
        try {
            assertEquals(2, migrated.schemaVersion());
            assertEquals(0, count(migrated, "SELECT COUNT(*) FROM net_hourly"));
        } finally {
            migrated.close();
        }
    }

    // ---------- saatlik ozet ----------

    @Test
    void aggregatesCompleteHoursWithCrcAcrossBoundaryAndResets() throws Exception {
        samples.insert(line(T0, "Up", 120, 40000, 100));
        samples.insert(line(T0 + 15 * M, "Up", -510, 40000, 110));   // gecersiz SNR
        samples.insert(line(T0 + 30 * M, "Down", 0, 0, 105));        // sayac sifirlandi
        samples.insert(line(T0 + 45 * M, "Up", 100, 39000, 120));
        samples.insert(line(T0 + H, "Up", 110, 41000, 130));         // onceki saatten +10
        samples.insert(line(T0 + H + 30 * M, "Up", 90, 41000, 140));
        samples.insert(line(T0 + 2 * H + M, "Up", 90, 41000, 141));  // tamamlanmamis saat

        RetentionJob.Result r = job.run(T0 + 2 * H + 5 * M, 30, 365);
        assertEquals(2, r.lineHours());

        List<HourlyRepository.LineHour> hours = hourly.lineHours(0, Long.MAX_VALUE);
        assertEquals(2, hours.size());
        HourlyRepository.LineHour h0 = hours.get(0);
        assertEquals(T0, h0.hour());
        assertEquals(4, h0.agg().samples());
        assertEquals(3, h0.agg().upSamples());
        assertEquals(2, h0.agg().snrDownN(), "gecersiz SNR ve kopuk olcum haric");
        assertEquals(220, h0.agg().snrDownSum());
        assertEquals(100, h0.agg().snrDownMin());
        assertEquals(39000, h0.agg().syncDownMin());
        assertEquals(10 + 15, h0.crcInc(), "sifirlanma adimi sayilmaz");
        assertEquals("VDSL2", h0.agg().modulation());

        HourlyRepository.LineHour h1 = hours.get(1);
        assertEquals(T0 + H, h1.hour());
        assertEquals(10 + 10, h1.crcInc(), "saat sinirindaki adim sonraki saate yazilir");
        assertEquals(10.0, h1.agg().toStats().avgSnrDownDb(), 1e-9);
    }

    @Test
    void aggregationIsIdempotentAndContinuesWhereItStopped() throws Exception {
        samples.insert(line(T0, "Up", 120, 40000, 100));
        samples.insert(line(T0 + 30 * M, "Up", 120, 40000, 104));
        job.run(T0 + H + M, 30, 365);
        assertEquals(0, job.run(T0 + H + 2 * M, 30, 365).lineHours());
        assertEquals(1, hourly.lineHours(0, Long.MAX_VALUE).size());

        samples.insert(line(T0 + H + 10 * M, "Up", 120, 40000, 107));
        assertEquals(1, job.run(T0 + 2 * H, 30, 365).lineHours());
        List<HourlyRepository.LineHour> hours = hourly.lineHours(0, Long.MAX_VALUE);
        assertEquals(2, hours.size());
        assertEquals(4, hours.get(0).crcInc(), "onceki saat tekrar yazilmadi");
        assertEquals(3, hours.get(1).crcInc(), "yeni saat, onceki saatin son olcumunden devam eder");
    }

    @Test
    void netHoursExcludeUnmeasuredLatency() throws Exception {
        net.insert(netSample(T0, true, 20, 2, 0));
        net.insert(netSample(T0 + 15 * M, true, 30, 4, 25));
        net.insert(netSample(T0 + 30 * M, false, -1, -1, 100));
        job.run(T0 + H, 30, 365);

        List<HourlyRepository.NetHour> hours = hourly.netHours(0, Long.MAX_VALUE);
        assertEquals(1, hours.size());
        NetAggregate a = hours.get(0).agg();
        assertEquals(3, a.samples());
        assertEquals(2, a.online());
        assertEquals(2, a.latencyN());
        assertEquals(125, a.lossSum());
        NetSampleRepository.Summary s = a.toSummary();
        assertEquals(25.0, s.avgLatencyMs(), 1e-9);
        assertEquals(3.0, s.avgJitterMs(), 1e-9);
        assertEquals(125 / 3.0, s.avgLossPct(), 1e-9);
        // Ham araliktan hesaplanan ozetle ayni
        NetSampleRepository.Summary raw = net.summaryRange(T0, T0 + H);
        assertEquals(raw.avgLatencyMs(), s.avgLatencyMs(), 1e-9);
        assertEquals(raw.onlinePct(), s.onlinePct(), 1e-9);
    }

    @Test
    void rawRowsAreDeletedOnlyAfterAggregationAtHourBoundary() throws Exception {
        for (long ts = T0; ts < T0 + 3 * H; ts += 15 * M) {
            samples.insert(line(ts, "Up", 120, 40000, ts / M));
            net.insert(netSample(ts, true, 20, 2, 0));
        }
        // Hic ozet yokken saklama suresi gecmis olsun: once ozetlenmeli, sonra silinmeli
        long now = T0 + 30 * DAY + H + 20 * M;
        RetentionJob.Result r = job.run(now, 30, 365);

        assertEquals(3, r.lineHours());
        assertEquals(3, r.netHours());
        // Kesim noktasi saat basina hizali: now - 30 gun = T0 + 1:20 -> T0 + 1:00
        assertEquals(4, r.samplesDeleted());
        assertEquals(T0 + H, samples.firstTs());
        assertEquals(T0 + H, net.firstTs());
        assertEquals(3, hourly.lineHours(0, Long.MAX_VALUE).size(), "silinen saatin ozeti duruyor");
        assertEquals(12, hourly.lineHours(0, Long.MAX_VALUE).stream().mapToInt(h -> h.agg().samples()).sum());
    }

    @Test
    void hourlyRowsExpireAfterTheirOwnRetention() throws Exception {
        samples.insert(line(T0, "Up", 120, 40000, 1));
        samples.insert(line(T0 + 10 * DAY, "Up", 120, 40000, 2));
        job.run(T0 + 10 * DAY + H, 30, 365);
        assertEquals(2, hourly.lineHours(0, Long.MAX_VALUE).size());

        RetentionJob.Result r = job.run(T0 + 370 * DAY, 30, 365);
        assertEquals(0, count(db, "SELECT COUNT(*) FROM samples"));
        assertEquals(1, r.hourlyDeleted());
        assertEquals(T0 + 10 * DAY, hourly.firstLineHour());
    }

    @Test
    void hourlyRetentionIsNeverShorterThanRaw() throws Exception {
        samples.insert(line(T0, "Up", 120, 40000, 1));
        job.run(T0 + 20 * DAY, 30, 7);
        assertEquals(T0, hourly.firstLineHour());
    }

    @Test
    void timelineQueriesSkipTheHourContainingTs() throws Exception {
        samples.insert(line(T0, "Up", 120, 40000, 1));
        samples.insert(line(T0 + 10 * M, "Down", 0, 0, 1));
        samples.insert(line(T0 + H + 10 * M, "Down", 0, 0, 1));
        samples.insert(line(T0 + 2 * H + 10 * M, "Up", 120, 40000, 1));
        job.run(T0 + 3 * H, 30, 365);

        assertEquals(T0 + 2 * H, hourly.firstUpHourAfter(T0 + 10 * M, Long.MAX_VALUE));
        assertEquals(-1, hourly.firstUpHourAfter(T0 + 10 * M, T0 + 2 * H));
        assertEquals(T0 + H, hourly.lastDownHourBetween(T0 + 10 * M, T0 + 2 * H));
        assertEquals(-1, hourly.lastDownHourBetween(T0 + H + 10 * M, T0 + 3 * H));
    }

    @Test
    void aggregatesMonthOfDataQuickly() throws Exception {
        // Gercek kurulumdaki gibi ~30 gunluk 15 sn'lik ham veri (~170 bin satir)
        db.conn().setAutoCommit(false);
        long end = T0 + 30 * DAY;
        long n = 0;
        for (long ts = T0; ts < end; ts += 15_000, n++) {
            samples.insert(line(ts, "Up", 120, 40000, n));
            net.insert(netSample(ts, true, 20, 2, 0));
        }
        db.conn().commit();
        db.conn().setAutoCommit(true);

        long t = System.nanoTime();
        RetentionJob.Result r = job.run(end + 5 * DAY, 30, 365);
        long ms = (System.nanoTime() - t) / 1_000_000;
        assertEquals(30 * 24, r.lineHours());
        assertEquals(30 * 24, r.netHours());
        assertEquals(n - 1, hourly.lineHours(0, Long.MAX_VALUE).stream()
                .mapToLong(HourlyRepository.LineHour::crcInc).sum(), "tum CRC artisi saatlere dagitildi");
        assertTrue(ms < 10_000, "ozet " + ms + " ms surdu");
    }
}
