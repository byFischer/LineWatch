package com.emre.linewatch.alert;

import com.emre.linewatch.collector.LineSample;
import com.emre.linewatch.collector.ModemCollector.CollectorException;
import com.emre.linewatch.collector.NetSample;
import com.emre.linewatch.config.Settings;
import com.emre.linewatch.storage.EventRepository;
import com.emre.linewatch.storage.LineEvent;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Ornekleri kurallara gore degerlendirir, olay uretir ve bildirim gonderir.
 * Durum makinesi: hat up/down gecisleri, CRC delta, dusuk SNR, retrain.
 */
public class AlertEngine {

    private final Settings settings;
    private final EventRepository events;
    private final com.emre.linewatch.storage.SampleRepository samples;
    private final List<Notifier> notifiers;
    private Consumer<LineEvent> eventListener = e -> {};

    private LineSample last;
    private long downSince = -1;          // hat/modem kopma baslangici
    private boolean wasDown = false;
    private int lowSnrStreak = 0;
    private Boolean netUp = null;         // internet erisimi (null = henuz bilinmiyor)
    private long netDownSince = -1;
    private NetSample lastNet;
    private int highLatencyStreak = 0;
    private int lossStreak = 0;
    private final Map<LineEvent.Type, Long> lastNotified = new EnumMap<>(LineEvent.Type.class);

    public AlertEngine(Settings settings, EventRepository events,
                       com.emre.linewatch.storage.SampleRepository samples, List<Notifier> notifiers) {
        this.settings = settings;
        this.events = events;
        this.samples = samples;
        this.notifiers = notifiers;
    }

    public void setEventListener(Consumer<LineEvent> l) {
        this.eventListener = l;
    }

    public synchronized void onSample(LineSample s) {
        if (!s.isUp()) {
            markDown("Hat durumu: " + s.status());
            last = s;
            return;
        }

        if (wasDown) {
            long durSec = downSince > 0 ? (s.ts() - downSince) / 1000 : 0;
            emit(LineEvent.Type.LINK_UP,
                    "Hat geri geldi (" + fmtDur(durSec) + " kesinti). Senkron: "
                            + s.syncDown() / 1000.0 + "/" + s.syncUp() / 1000.0 + " Mbps",
                    durSec, false, true);
            wasDown = false;
            downSince = -1;
        }

        if (last != null && last.isUp()) {
            // CRC delta (negatifse sayac sifirlanmis: reboot/retrain -> yok say)
            long delta = s.crcDown() - last.crcDown();
            if (delta >= settings.crcDeltaThreshold()) {
                emit(LineEvent.Type.CRC_BURST,
                        "Son " + (s.ts() - last.ts()) / 1000 + " sn'de " + delta + " CRC hatası. "
                                + "SNR: " + s.snrDown() / 10.0 + " dB",
                        0, true, true);
            }

            // Retrain: sync hizi degisti (yeniden senkron olmus)
            if (s.syncDown() != last.syncDown() || s.syncUp() != last.syncUp()) {
                emit(LineEvent.Type.RETRAIN,
                        "Senkron değişti: " + last.syncDown() + " → " + s.syncDown() + " kbps (aşağı), "
                                + last.syncUp() + " → " + s.syncUp() + " kbps (yukarı)",
                        0, false, true);
            }
        }

        // Dusuk SNR: art arda 3 orneklemede esigin altinda
        if (s.snrDown() / 10.0 < settings.snrThresholdDb()) {
            if (++lowSnrStreak == 3) {
                emit(LineEvent.Type.LOW_SNR,
                        "SNR marjı " + s.snrDown() / 10.0 + " dB (eşik: " + settings.snrThresholdDb() + " dB)",
                        0, true, true);
            }
        } else {
            lowSnrStreak = 0;
        }

        last = s;
    }

    public synchronized void onError(CollectorException e) {
        switch (e.kind()) {
            case UNREACHABLE -> markDown("Modeme ulaşılamıyor (modem kapalı ya da yeniden başlıyor olabilir)");
            case AUTH_FAILED -> emit(LineEvent.Type.COLLECTOR_ERROR,
                    "Modem şifresi hatalı görünüyor — Ayarlar'dan kontrol et", 0, true, true);
            case PROTOCOL -> emit(LineEvent.Type.COLLECTOR_ERROR, String.valueOf(e.getMessage()), 0, false, true);
        }
    }

    /**
     * Internet erisim olcumunu degerlendirir: erisim kesilmesi/gelmesi, yuksek
     * gecikme ve paket kaybi. Modem yapilandirilmamis olsa da calisir — modemsiz
     * kurulumda uygulamanin tek olay kaynagi budur.
     */
    public synchronized void onNetSample(NetSample net) {
        lastNet = net;
        onConnectivity(net.online());
        if (!net.online()) {
            // Cevrimdisiyken gecikme/kayip zaten anlamsiz; sayaclar sifirlanir ki
            // erisim gelince eski seri uzerinden yanlis uyari uretilmesin.
            highLatencyStreak = 0;
            lossStreak = 0;
            return;
        }

        if (net.hasLatency() && net.latencyMs() > settings.latencyThresholdMs()) {
            if (++highLatencyStreak == 3) {
                emit(LineEvent.Type.HIGH_LATENCY,
                        "Gecikme " + Math.round(net.latencyMs()) + " ms (eşik: "
                                + settings.latencyThresholdMs() + " ms)"
                                + (net.hasJitter() ? ", jitter " + Math.round(net.jitterMs()) + " ms" : "")
                                + " · hedef: " + net.target(),
                        0, false, true);
            }
        } else {
            highLatencyStreak = 0;
        }

        // Tek turluk kayip gurultu olabilir; art arda iki turda tekrarlarsa raporlanir.
        if (net.lossPct() > 0 && net.lossPct() >= settings.lossThresholdPct()) {
            if (++lossStreak == 2) {
                emit(LineEvent.Type.PACKET_LOSS,
                        "Paket kaybı %" + net.lossPct() + " (" + net.received() + "/"
                                + net.sent() + " yoklama yanıtlandı, hedef: " + net.target() + ")",
                        0, true, true);
            }
        } else {
            lossStreak = 0;
        }
    }

    /** Internet erisiminin var/yok gecisleri. */
    private void onConnectivity(boolean online) {
        if (netUp != null && netUp == online) return;
        boolean firstReading = netUp == null;
        netUp = online;
        if (!online) {
            netDownSince = System.currentTimeMillis();
            // Hat zaten kopuksa LINK_DOWN durumu anlatiyor; ayrica NET_DOWN uretmek
            // ayni olayi iki kez bildirmek olur.
            if (!firstReading && !wasDown) {
                boolean dslUp = last != null && last.isUp();
                emit(LineEvent.Type.NET_DOWN,
                        dslUp
                                ? "DSL senkron ama internete çıkış yok — sorun büyük ihtimalle sağlayıcı tarafında"
                                : "İnternet erişimi kesildi",
                        0, true, false);
            }
        } else if (!firstReading) {
            long durSec = netDownSince > 0 ? (System.currentTimeMillis() - netDownSince) / 1000 : 0;
            emit(LineEvent.Type.NET_UP, "İnternet erişimi geri geldi (" + fmtDur(durSec) + ")",
                    durSec, false, false);
            netDownSince = -1;
        }
    }

    public synchronized NetSample lastNet() {
        return lastNet;
    }

    public synchronized boolean isNetUp() {
        return netUp == null || netUp;
    }

    private void markDown(String detail) {
        if (!wasDown) {
            wasDown = true;
            downSince = System.currentTimeMillis();
            emit(LineEvent.Type.LINK_DOWN, detail + preDropAnalysis(), 0, true, false);
        }
    }

    /** Kopmadan onceki 10 dakikanin ozeti: SNR gidisati + CRC artisi. */
    private String preDropAnalysis() {
        try {
            long now = System.currentTimeMillis();
            List<LineSample> window = samples.findRange(now - 10 * 60_000, now, 600);
            if (window.size() < 2) return "";
            double minSnr = Double.MAX_VALUE, sumSnr = 0;
            long crcDelta = 0;
            LineSample prev = null;
            int upCount = 0;
            for (LineSample s : window) {
                if (!s.isUp()) continue;
                upCount++;
                double snr = s.snrDown() / 10.0;
                minSnr = Math.min(minSnr, snr);
                sumSnr += snr;
                if (prev != null && s.crcDown() >= prev.crcDown()) crcDelta += s.crcDown() - prev.crcDown();
                prev = s;
            }
            if (upCount == 0) return "";
            return String.format(" | Kopma öncesi 10 dk: SNR ort. %.1f / en düşük %.1f dB, +%d CRC",
                    sumSnr / upCount, minSnr, crcDelta);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Olayi DB'ye yazar, dinleyiciye iletir ve (cooldown izin veriyorsa) bildirim gonderir.
     * LINK_DOWN/LINK_UP her zaman bildirilir; digerleri cooldown'a tabidir.
     */
    private void emit(LineEvent.Type type, String detail, long durationSec,
                      boolean urgent, boolean cooldownApplies) {
        try {
            LineEvent ev = events.insert(type, detail, durationSec);
            eventListener.accept(ev);
        } catch (Exception ignored) {
        }
        long now = System.currentTimeMillis();
        if (cooldownApplies && type != LineEvent.Type.LINK_UP) {
            Long prev = lastNotified.get(type);
            if (prev != null && now - prev < settings.alertCooldownMinutes() * 60_000L) return;
        }
        lastNotified.put(type, now);
        for (Notifier n : notifiers) {
            try {
                n.notify("LineWatch: " + type.label, detail, urgent);
            } catch (Exception ignored) {
            }
        }
    }

    private static String fmtDur(long sec) {
        if (sec < 60) return sec + " sn";
        if (sec < 3600) return sec / 60 + " dk " + sec % 60 + " sn";
        return sec / 3600 + " sa " + (sec % 3600) / 60 + " dk";
    }
}
