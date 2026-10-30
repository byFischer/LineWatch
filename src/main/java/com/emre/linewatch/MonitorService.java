package com.emre.linewatch;

import com.emre.linewatch.alert.AlertEngine;
import com.emre.linewatch.alert.TelegramNotifier;
import com.emre.linewatch.collector.LineSample;
import com.emre.linewatch.collector.ModemCollector;
import com.emre.linewatch.collector.ModemCollector.CollectorException;
import com.emre.linewatch.collector.NetProbe;
import com.emre.linewatch.collector.NetSample;
import com.emre.linewatch.config.Log;
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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Periyodik toplama dongusu. Iki olcum ayri is parcaciklarinda ayni aralikla
 * yapilir: modem (DSL) ve internet erisimi (gecikme/jitter/kayip). Boylece
 * takilan bir modem HTTP cagrisi internet olcumlerini geciktirmez.
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
    private static final Logger LOG = Log.get(MonitorService.class);

    private final NetProbe probe;
    /** Modem toplama, yeniden baslatma ve toplayici kapatma bu is parcaciginda. */
    private final ScheduledExecutorService modemScheduler = daemonScheduler("linewatch-modem");
    /** Internet olcumu ve gunluk temizlik bu is parcaciginda. */
    private final ScheduledExecutorService netScheduler = daemonScheduler("linewatch-net");

    private volatile ModemCollector collector;
    private ScheduledFuture<?> modemTask;
    private ScheduledFuture<?> netTask;
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

    private static ScheduledExecutorService daemonScheduler(String name) {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    public void setCollector(ModemCollector collector) {
        ModemCollector old = this.collector;
        this.collector = collector;
        if (old != null) modemScheduler.execute(old::close);
    }

    /** Modem izleme kapatildiginda toplayiciyi birakir (dongu calismaya devam eder). */
    public void clearCollector() {
        setCollector(null);
    }

    public synchronized void start() {
        schedule();
        // gunluk temizlik
        netScheduler.scheduleWithFixedDelay(this::cleanup, 1, 24 * 60, TimeUnit.MINUTES);
    }

    /** Ayarlar degistiginde (aralik, sifre) donguyu yeni degerlerle yeniden kurar. */
    public synchronized void restart(ModemCollector newCollector) {
        setCollector(newCollector);
        schedule();
    }

    private synchronized void schedule() {
        if (modemTask != null) modemTask.cancel(false);
        if (netTask != null) netTask.cancel(false);
        long period = Math.max(5, settings.pollSeconds());
        modemTask = modemScheduler.scheduleWithFixedDelay(this::modemTick, 0, period, TimeUnit.SECONDS);
        netTask = netScheduler.scheduleWithFixedDelay(this::netTick, 0, period, TimeUnit.SECONDS);
    }

    private void modemTick() {
        LineSample s = tickModem();
        // Ag olcumu kapaliysa Telegram kuyrugu icin DSL durumu en iyi tahmin.
        if (!settings.netProbeEnabled()) flushTelegram(s != null ? s.isUp() : isLastSampleUp());
    }

    private void netTick() {
        NetSample net = tickNet();
        if (net != null) flushTelegram(net.online());
    }

    private boolean isLastSampleUp() {
        LineSample s = lastSample;
        return s != null && s.isUp();
    }

    /** Kuyruktaki Telegram mesajlari ancak cikis varken gonderilebilir. */
    private void flushTelegram(boolean online) {
        try {
            if (online && telegram.hasPending()) telegram.flush();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Telegram kuyrugu gonderilemedi", e);
        }
    }

    /** Olcum yapildiysa sonucu, toplayici yoksa ya da hata olduysa null dondurur. */
    private LineSample tickModem() {
        ModemCollector c = collector;
        if (c == null) return null;
        try {
            LineSample s = c.collect();
            lastSample = s;
            try {
                samples.insert(s);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "DB yazma hatasi", e);
            }
            alerts.onSample(s);
            for (Consumer<LineSample> l : sampleListeners) l.accept(s);
            for (Consumer<String> l : statusListeners) l.accept(null);
            return s;
        } catch (CollectorException e) {
            alerts.onError(e);
            for (Consumer<String> l : statusListeners) l.accept(e.getMessage());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Modem olcumunde beklenmeyen hata", e);
            for (Consumer<String> l : statusListeners) l.accept("Beklenmeyen hata: " + e.getMessage());
        }
        return null;
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
                LOG.log(Level.WARNING, "Ag olcumu yazilamadi", e);
            }
            alerts.onNetSample(n);
            for (Consumer<NetSample> l : netListeners) l.accept(n);
            return n;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Ag olcumu hatasi", e);
            return null;
        }
    }

    private void cleanup() {
        // Once saatlik ozet, sonra ham olcum silme (bkz. RetentionJob); hata olursa hicbir sey silinmez.
        try {
            new com.emre.linewatch.storage.RetentionJob(samples.database()).run(System.currentTimeMillis(),
                    settings.retentionDays(), settings.hourlyRetentionDays());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Saklama/ozet isi basarisiz", e);
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
        modemScheduler.execute(() -> {
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
        netScheduler.shutdownNow();
        modemScheduler.shutdownNow();
        ModemCollector c = collector;
        if (c != null) c.close();
    }
}
