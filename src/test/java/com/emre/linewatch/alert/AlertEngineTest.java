package com.emre.linewatch.alert;

import com.emre.linewatch.collector.LineSample;
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
        Settings settings = Settings.load(); // varsayilanlar: crc esik 50, snr 6.0, cooldown 15dk
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
}
