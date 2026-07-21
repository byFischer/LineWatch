package com.emre.linewatch;

import com.emre.linewatch.alert.AlertEngine;
import com.emre.linewatch.alert.TelegramNotifier;
import com.emre.linewatch.collector.LineSample;
import com.emre.linewatch.collector.ModemCollector;
import com.emre.linewatch.collector.ModemCollector.CollectorException;
import com.emre.linewatch.config.Settings;
import com.emre.linewatch.storage.SampleRepository;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Periyodik toplama dongusu: collect -> kaydet -> alert -> UI dinleyicileri. */
public class MonitorService {

    private final Settings settings;
    private final SampleRepository samples;
    private final AlertEngine alerts;
    private final TelegramNotifier telegram;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "linewatch-monitor");
                t.setDaemon(true);
                return t;
            });

    private volatile ModemCollector collector;
    private ScheduledFuture<?> task;
    private final List<Consumer<LineSample>> sampleListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> statusListeners = new CopyOnWriteArrayList<>();
    private volatile LineSample lastSample;

    public MonitorService(Settings settings, SampleRepository samples,
                          AlertEngine alerts, TelegramNotifier telegram) {
        this.settings = settings;
        this.samples = samples;
        this.alerts = alerts;
        this.telegram = telegram;
    }

    public void setCollector(ModemCollector collector) {
        ModemCollector old = this.collector;
        this.collector = collector;
        if (old != null) scheduler.execute(old::close);
    }

    public synchronized void start() {
        if (task != null) task.cancel(false);
        task = scheduler.scheduleWithFixedDelay(this::tick, 0,
                Math.max(5, settings.pollSeconds()), TimeUnit.SECONDS);
        // gunluk temizlik
        scheduler.scheduleWithFixedDelay(this::cleanup, 1, 24 * 60, TimeUnit.MINUTES);
    }

    /** Ayarlar degistiginde (aralik, sifre) donguyu yeni degerlerle yeniden kurar. */
    public synchronized void restart(ModemCollector newCollector) {
        setCollector(newCollector);
        if (task != null) task.cancel(false);
        task = scheduler.scheduleWithFixedDelay(this::tick, 0,
                Math.max(5, settings.pollSeconds()), TimeUnit.SECONDS);
    }

    private void tick() {
        ModemCollector c = collector;
        if (c == null) return;
        try {
            LineSample s = c.collect();
            lastSample = s;
            try {
                samples.insert(s);
            } catch (Exception e) {
                System.err.println("DB yazma hatasi: " + e.getMessage());
            }
            alerts.onSample(s);
            if (s.isUp()) alerts.onConnectivity(com.emre.linewatch.collector.ConnectivityChecker.isOnline());
            if (telegram.hasPending()) telegram.flush();
            for (Consumer<LineSample> l : sampleListeners) l.accept(s);
            for (Consumer<String> l : statusListeners) l.accept(null);
        } catch (CollectorException e) {
            alerts.onError(e);
            for (Consumer<String> l : statusListeners) l.accept(e.getMessage());
        } catch (Exception e) {
            for (Consumer<String> l : statusListeners) l.accept("Beklenmeyen hata: " + e.getMessage());
        }
    }

    private void cleanup() {
        try {
            samples.deleteOlderThan(System.currentTimeMillis()
                    - settings.retentionDays() * 24L * 3600 * 1000);
        } catch (Exception ignored) {
        }
    }

    /**
     * Modemi asenkron yeniden baslatir; sonucu callback'e bildirir
     * (null = basari, aksi halde hata mesaji).
     */
    public void rebootModem(Consumer<String> callback) {
        ModemCollector c = collector;
        if (c == null) {
            callback.accept("Toplayici hazir degil (once modem sifresini kaydet)");
            return;
        }
        scheduler.execute(() -> {
            try {
                c.reboot();
                callback.accept(null);
            } catch (Exception e) {
                callback.accept(e.getMessage());
            }
        });
    }

    public void addSampleListener(Consumer<LineSample> l) { sampleListeners.add(l); }
    public void addStatusListener(Consumer<String> l) { statusListeners.add(l); }
    public LineSample lastSample() { return lastSample; }

    public void shutdown() {
        scheduler.shutdownNow();
        ModemCollector c = collector;
        if (c != null) c.close();
    }
}
