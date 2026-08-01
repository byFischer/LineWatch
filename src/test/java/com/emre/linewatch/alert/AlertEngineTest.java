package com.emre.linewatch.alert;

import com.emre.linewatch.collector.LineSample;
import com.emre.linewatch.collector.NetSample;
import com.emre.linewatch.config.Settings;
import com.emre.linewatch.storage.Database;
import com.emre.linewatch.storage.EventRepository;
import com.emre.linewatch.storage.LineEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AlertEngineTest {

    private AlertEngine engine;
    private final List<String> notifications = new ArrayList<>();
    private final List<LineEvent> events = new ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        Database db = new Database(":memory:");
        EventRepository repo = new EventRepository(db);
        var sampleRepo = new com.emre.linewatch.storage.SampleRepository(db);
        // Settings.load() gercek %APPDATA% dosyasini okur; esikler burada acikca
        // sabitlenmezse testler calistiran kisinin ayarlarina gore kirilabilir.
        Settings settings = Settings.load();
        settings.crcDeltaThreshold(50);
        settings.snrThresholdDb(6.0);
        settings.latencyThresholdMs(150);
        settings.lossThresholdPct(5);
        settings.alertCooldownMinutes(15);
        engine = new AlertEngine(settings, repo, sampleRepo, List.of((t, m, u) -> notifications.add(t)));
        engine.setEventListener(events::add);
    }

    private LineSample sample(long ts, String status, long crc, int snrDown, int syncDown) {
        return new LineSample(ts, status, "VDSL2", syncDown, 7460, 39611, 7461,
                snrDown, 81, 174, 44, 138, 67, crc, 0, 0, 0, 0, 0, 0, 0);
    }

    @Test
    void crcBurstTriggersAlert() {
        engine.onSample(sample(1000, "Up", 1000, 117, 24574));
        engine.onSample(sample(2000, "Up", 1100, 117, 24574)); // +100 >= esik 50
        assertTrue(events.stream().anyMatch(e -> e.type() == LineEvent.Type.CRC_BURST));
    }

    @Test
    void crcCounterResetDoesNotTrigger() {
        engine.onSample(sample(1000, "Up", 400000, 117, 24574));
        engine.onSample(sample(2000, "Up", 5, 117, 24574)); // reboot: sayac sifirlandi
        assertFalse(events.stream().anyMatch(e -> e.type() == LineEvent.Type.CRC_BURST));
    }

    @Test
    void linkDownThenUpProducesBothEvents() {
        engine.onSample(sample(1000, "Up", 0, 117, 24574));
        engine.onSample(sample(2000, "Down", 0, 0, 0));
        engine.onSample(sample(3000, "Down", 0, 0, 0)); // ikinci down tekrar olay uretmemeli
        engine.onSample(sample(4000, "Up", 0, 117, 24574));
        assertEquals(1, events.stream().filter(e -> e.type() == LineEvent.Type.LINK_DOWN).count());
        assertEquals(1, events.stream().filter(e -> e.type() == LineEvent.Type.LINK_UP).count());
    }

    @Test
    void lowSnrNeedsThreeConsecutiveSamples() {
        engine.onSample(sample(1000, "Up", 0, 40, 24574));  // 4.0 dB < 6.0
        engine.onSample(sample(2000, "Up", 0, 40, 24574));
        assertFalse(events.stream().anyMatch(e -> e.type() == LineEvent.Type.LOW_SNR));
        engine.onSample(sample(3000, "Up", 0, 40, 24574));
        assertTrue(events.stream().anyMatch(e -> e.type() == LineEvent.Type.LOW_SNR));
    }

    @Test
    void retrainDetectedOnSyncChange() {
        engine.onSample(sample(1000, "Up", 0, 117, 24574));
        engine.onSample(sample(2000, "Up", 0, 117, 19999));
        assertTrue(events.stream().anyMatch(e -> e.type() == LineEvent.Type.RETRAIN));
    }

    // ---------- internet erisim olcumu ----------

    private static NetSample online(double latencyMs) {
        return new NetSample(System.currentTimeMillis(), true, latencyMs, 2, 0, 3, 3, "test");
    }

    private static NetSample lossy(int lossPct) {
        return new NetSample(System.currentTimeMillis(), true, 20, 2, lossPct, 3, 2, "test");
    }

    private static NetSample offline() {
        return NetSample.offline(System.currentTimeMillis(), 3);
    }

    private boolean has(LineEvent.Type type) {
        return events.stream().anyMatch(e -> e.type() == type);
    }

    private long count(LineEvent.Type type) {
        return events.stream().filter(e -> e.type() == type).count();
    }

    @Test
    void highLatencyNeedsThreeConsecutiveSamples() {
        engine.onNetSample(online(200));   // esik 150 ms
        engine.onNetSample(online(200));
        assertFalse(has(LineEvent.Type.HIGH_LATENCY));
        engine.onNetSample(online(200));
        assertTrue(has(LineEvent.Type.HIGH_LATENCY));
    }

    @Test
    void normalLatencyResetsTheStreak() {
        engine.onNetSample(online(200));
        engine.onNetSample(online(200));
        engine.onNetSample(online(20));    // seriyi kirar
        engine.onNetSample(online(200));
        engine.onNetSample(online(200));
        assertFalse(has(LineEvent.Type.HIGH_LATENCY));
    }

    @Test
    void packetLossNeedsTwoConsecutiveSamples() {
        engine.onNetSample(lossy(33));
        assertFalse(has(LineEvent.Type.PACKET_LOSS));
        engine.onNetSample(lossy(33));
        assertTrue(has(LineEvent.Type.PACKET_LOSS));
    }

    @Test
    void lossBelowThresholdIsIgnored() {
        engine.onNetSample(lossy(3));      // esik %5
        engine.onNetSample(lossy(3));
        assertFalse(has(LineEvent.Type.PACKET_LOSS));
    }

    @Test
    void netDownThenUpProducesBothEventsOnce() {
        engine.onNetSample(online(20));    // ilk okuma: olay uretmez
        engine.onNetSample(offline());
        engine.onNetSample(offline());     // ikinci kez olay uretmemeli
        engine.onNetSample(online(20));
        assertEquals(1, count(LineEvent.Type.NET_DOWN));
        assertEquals(1, count(LineEvent.Type.NET_UP));
    }

    @Test
    void netDownIsSuppressedWhileTheLineIsDown() {
        engine.onSample(sample(1000, "Up", 0, 117, 24574));
        engine.onNetSample(online(20));                   // ilk okuma
        engine.onSample(sample(2000, "Down", 0, 0, 0));   // hat koptu
        engine.onNetSample(offline());

        assertTrue(has(LineEvent.Type.LINK_DOWN));
        // Hat kopukken internetin de gitmis olmasi ayri bir olay degil
        assertFalse(has(LineEvent.Type.NET_DOWN));
    }

    @Test
    void offlineSampleDoesNotTriggerLatencyOrLossAlerts() {
        engine.onNetSample(online(200));
        engine.onNetSample(online(200));
        engine.onNetSample(offline());     // seri sifirlanmali, kayip %100 sayilmamali
        engine.onNetSample(online(200));
        assertFalse(has(LineEvent.Type.HIGH_LATENCY));
        assertFalse(has(LineEvent.Type.PACKET_LOSS));
    }
}
