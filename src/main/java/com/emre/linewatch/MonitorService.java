package com.emre.linewatch;

import com.emre.linewatch.alert.AlertEngine;
import com.emre.linewatch.alert.TelegramNotifier;
import com.emre.linewatch.collector.LineSample;
import com.emre.linewatch.collector.ModemCollector;
import com.emre.linewatch.collector.ModemCollector.CollectorException;
import com.emre.linewatch.collector.NetProbe;
import com.emre.linewatch.collector.NetSample;
import com.emre.linewatch.config.Settings;
import com.emre.linewatch.storage.NetSampleRepository;
import com.emre.linewatch.storage.SampleRepository;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Periyodik toplama dongusu. Her turda iki olcum yapilir:
 * modem (DSL) ve internet erisimi (gecikme/jitter/kayip).
 *
 * <p>Modem yapilandirilmamis ya da desteklenmiyorsa dongu durmaz — internet
 * olcumu tek basina calisir, uygulama yine anlamli veri uretir.
 */
public class MonitorService {

    private final Settings settings;
    private final SampleRepository samples;
    private final NetSampleRepository netSamples;
    private final AlertEngine alerts;
    private final TelegramNotifier telegram;
    private final NetProbe probe;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "linewatch-monitor");
                t.setDaemon(true);
                return t;
            });

    private volatile ModemCollector collector;
    private ScheduledFuture<?> task;
    private final List<Consumer<LineSample>> sampleListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<NetSample>> netListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> statusListeners = new CopyOnWriteArrayList<>();
    private volatile LineSample lastSample;
    private volatile NetSample lastNet;

    public MonitorService(Settings settings, SampleRepository samples, NetSampleRepository netSamples,
                          AlertEngine alerts, TelegramNotifier telegram) {
        this(settings, samples, netSamples, alerts, telegram, new NetProbe());
    }

    /** Test icin: yoklayici disaridan verilebilir. */
    public MonitorService(Settings settings, SampleRepository samples, NetSampleRepository netSamples,
                          AlertEngine alerts, TelegramNotifier telegram, NetProbe probe) {
        this.settings = settings;
        this.samples = samples;
        this.netSamples = netSamples;
        this.alerts = alerts;
        this.telegram = telegram;
        this.probe = probe;
    }

    public void setCollector(ModemCollector collector) {
        ModemCollector old = this.collector;
        this.collector = collector;
        if (old != null) scheduler.execute(old::close);
    }

    /** Modem izleme kapatildiginda toplayiciyi birakir (dongu calismaya devam eder). */
    public void clearCollector() {
        setCollector(null);
    }

    public synchronized void start() {
        schedule();
        // gunluk temizlik
        scheduler.scheduleWithFixedDelay(this::cleanup, 1, 24 * 60, TimeUnit.MINUTES);
    }

    /** Ayarlar degistiginde (aralik, sifre) donguyu yeni degerlerle yeniden kurar. */
    public synchronized void restart(ModemCollector newCollector) {
        setCollector(newCollector);
        schedule();
    }

    private synchronized void schedule() {
        if (task != null) task.cancel(false);
        task = scheduler.scheduleWithFixedDelay(this::tick, 0,
                Math.max(5, settings.pollSeconds()), TimeUnit.SECONDS);
    }

    private void tick() {
        tickModem();
        NetSample net = tickNet();

        // Kuyruktaki Telegram mesajlari ancak cikis varken gonderilebilir.
        // Ag olcumu kapaliysa DSL durumu en iyi tahmin.
        LineSample s = lastSample;
        boolean online = net != null ? net.online() : (s != null && s.isUp());
        if (online && telegram.hasPending()) telegram.flush();
    }

    private void tickModem() {
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
            for (Consumer<LineSample> l : sampleListeners) l.accept(s);
            for (Consumer<String> l : statusListeners) l.accept(null);
        } catch (CollectorException e) {
            alerts.onError(e);
            for (Consumer<String> l : statusListeners) l.accept(e.getMessage());
        } catch (Exception e) {
            for (Consumer<String> l : statusListeners) l.accept("Beklenmeyen hata: " + e.getMessage());
        }
    }

    /** Olcum yapildiysa sonucu, ag izleme kapaliysa null dondurur. */
    private NetSample tickNet() {
        if (!settings.netProbeEnabled()) return null;
        try {
            NetSample n = probe.measure();
            lastNet = n;
            try {
                netSamples.insert(n);
            } catch (Exception e) {
                System.err.println("Ag olcumu yazilamadi: " + e.getMessage());
            }
            alerts.onNetSample(n);
            for (Consumer<NetSample> l : netListeners) l.accept(n);
            return n;
        } catch (Exception e) {
            System.err.println("Ag olcumu hatasi: " + e.getMessage());
            return null;
        }
    }

    private void cleanup() {
        long cutoff = System.currentTimeMillis() - settings.retentionDays() * 24L * 3600 * 1000;
        try {
            samples.deleteOlderThan(cutoff);
        } catch (Exception ignored) {
        }
        try {
            netSamples.deleteOlderThan(cutoff);
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
    public void addNetListener(Consumer<NetSample> l) { netListeners.add(l); }
    public void addStatusListener(Consumer<String> l) { statusListeners.add(l); }
    public LineSample lastSample() { return lastSample; }
    public NetSample lastNet() { return lastNet; }
    public boolean hasCollector() { return collector != null; }

    public void shutdown() {
        scheduler.shutdownNow();
        ModemCollector c = collector;
        if (c != null) c.close();
    }
}
