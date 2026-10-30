package com.emre.linewatch;

import com.emre.linewatch.alert.AlertEngine;
import com.emre.linewatch.alert.TelegramNotifier;
import com.emre.linewatch.collector.LineSample;
import com.emre.linewatch.collector.ModemCollector;
import com.emre.linewatch.collector.NetProbe;
import com.emre.linewatch.collector.NetSample;
import com.emre.linewatch.config.Settings;
import com.emre.linewatch.storage.Database;
import com.emre.linewatch.storage.EventRepository;
import com.emre.linewatch.storage.NetSampleRepository;
import com.emre.linewatch.storage.SampleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Modem ve internet olcumu ayri is parcaciklarinda: takilan bir modem cagrisi
 * internet olcumunu geciktirmemeli. Gercek aga/modeme cikilmaz.
 */
class MonitorServiceTest {

    private Database db;
    private ServerSocket server;
    private MonitorService monitor;
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void setup() throws Exception {
        db = new Database(":memory:");
        server = new ServerSocket(0);
        // Bos ayarlar: gercek %APPDATA% okunmaz, kaydedilmez.
        Settings settings = new Settings();
        SampleRepository samples = new SampleRepository(db);
        NetSampleRepository netSamples = new NetSampleRepository(db);
        AlertEngine alerts = new AlertEngine(settings, new EventRepository(db), samples, List.of());
        NetProbe probe = new NetProbe(
                List.of(new NetProbe.Target("127.0.0.1", server.getLocalPort(), "yerel")), 1, 500);
        monitor = new MonitorService(settings, samples, netSamples, alerts,
                new TelegramNotifier(settings), probe);
    }

    @AfterEach
    void teardown() throws Exception {
        release.countDown();
        monitor.shutdown();
        server.close();
        db.close();
    }

    @Test
    void hangingModemDoesNotBlockNetProbe() throws Exception {
        CountDownLatch modemEntered = new CountDownLatch(1);
        CountDownLatch netMeasured = new CountDownLatch(1);
        monitor.setCollector(new ModemCollector() {
            @Override
            public LineSample collect() {
                modemEntered.countDown();
                try {
                    release.await(); // takilan modem HTTP cagrisi
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("kapandi");
            }
        });
        monitor.addNetListener(n -> netMeasured.countDown());

        monitor.start();

        assertTrue(modemEntered.await(5, TimeUnit.SECONDS), "modem turu baslamadi");
        assertTrue(netMeasured.await(5, TimeUnit.SECONDS), "internet olcumu modem yuzunden bekledi");
        NetSample n = monitor.lastNet();
        assertNotNull(n);
        assertTrue(n.online());
    }

    @Test
    void rebootRunsWithoutCollectorCallback() {
        String[] result = new String[1];
        monitor.rebootModem(err -> result[0] = err);
        assertNotNull(result[0]);
    }
}
