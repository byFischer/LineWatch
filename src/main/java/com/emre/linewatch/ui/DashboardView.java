package com.emre.linewatch.ui;

import com.emre.linewatch.collector.LineSample;
import com.emre.linewatch.collector.NetSample;
import com.emre.linewatch.config.Settings;
import com.emre.linewatch.storage.EventRepository;
import com.emre.linewatch.storage.LineEvent;
import com.emre.linewatch.storage.NetSampleRepository;
import com.emre.linewatch.storage.SampleRepository;
import com.emre.linewatch.ui.widget.MetricCard;
import com.emre.linewatch.ui.widget.Role;
import com.emre.linewatch.ui.widget.TimeChart;
import com.emre.linewatch.ui.widget.UptimeStrip;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pano: saglik karti + 24 saat seridi + canli SNR grafigi + metrik kartlari.
 * Bento yerlesim — kart boyutlari onem sirasina gore degisir.
 */
public class DashboardView extends ScrollPane {

    private static final int STRIP_BLOCKS = 96;   // 24 saat / 15 dakika
    private static final long DAY_MS = 24L * 3600_000;
    private static final long HOUR_MS = 3600_000L;

    // saglik karti
    private final Circle dot = Ui.dot(5, "unknown");
    private final Label statusText = new Label("BEKLENİYOR");
    private final Label modulation = Ui.faint("");
    private final Label updated = Ui.faint("");
    private final Label headline = new Label("Hat durumu bekleniyor");
    private final Label detail = new Label("Modemden ilk ölçüm bekleniyor.");
    private final Label grade = new Label("—");
    private final Label scoreText = new Label("");
    private final Label lastEvent = Ui.faint("");

    // 24 saat
    private final UptimeStrip strip = new UptimeStrip();
    private final Label uptimePct = new Label("—");
    private final Label uptimeInfo = Ui.faint("son 24 saat");
    private final Label uninterrupted = Ui.faint("");

    // canli grafik
    private final TimeChart liveChart = new TimeChart().unit("dB").timeAxis(true);

    // metrikler
    private final MetricCard snrCard = new MetricCard("SNR MARJI");
    private final MetricCard syncCard = new MetricCard("SENKRON HIZI");
    private final MetricCard crcCard = new MetricCard("CRC HATALARI", true, true);
    private final MetricCard attenCard = new MetricCard("ZAYIFLAMA");
    private final MetricCard capacityCard = new MetricCard("HAT KAPASİTESİ");
    private final MetricCard todayCard = new MetricCard("BUGÜN", false, false);

    // internet erisim metrikleri (modem desteklenmese de dolar)
    private final MetricCard latencyCard = new MetricCard("GECİKME");
    private final MetricCard jitterCard = new MetricCard("JİTTER");
    private final MetricCard lossCard = new MetricCard("PAKET KAYBI", true, true);

    private final GridPane bento = new GridPane();
    private final Label liveTitle = Ui.cardTitle("SON 60 DAKİKA · SNR MARJI");
    private VBox heroCard;
    private VBox uptimeCard;
    private VBox liveCard;
    private HBox liveLegend;
    /** Modem izleniyor mu; false ise Pano internet olcumu uzerine kurulur. */
    private boolean modemMonitored = true;

    private final SampleRepository samples;
    private final NetSampleRepository netSamples;
    private final EventRepository events;
    private final Settings settings;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dashboard-stats");
        t.setDaemon(true);
        return t;
    });

    private LineSample previous;
    private volatile NetSample lastNetSample;
    private volatile long lastSampleTs;
    private Timeline pulse;
    /** Serit uzerinde fare yokken alt satirda gosterilen ozet. */
    private String summaryText = "son 24 saat";

    public DashboardView(SampleRepository samples, NetSampleRepository netSamples,
                         EventRepository events, Settings settings) {
        this.samples = samples;
        this.netSamples = netSamples;
        this.events = events;
        this.settings = settings;

        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);

        bento.setHgap(14);
        bento.setVgap(14);
        for (int i = 0; i < 3; i++) {
            ColumnConstraints c = new ColumnConstraints();
            c.setPercentWidth(100.0 / 3);
            bento.getColumnConstraints().add(c);
        }

        heroCard = buildHero();
        uptimeCard = buildUptimeCard();
        liveCard = buildLiveCard();
        rebuildLayout();

        VBox page = new VBox(bento);
        page.getStyleClass().add("page");
        setContent(page);

        // "son guncelleme 4 sn once" metnini saniye basinda tazele
        Timeline ticker = new Timeline(new javafx.animation.KeyFrame(Duration.seconds(1),
                e -> { if (lastSampleTs > 0) updated.setText("son güncelleme " + Fmt.ago(lastSampleTs)); }));
        ticker.setCycleCount(Timeline.INDEFINITE);
        ticker.play();
    }

    private static void add(GridPane grid, Region node, int col, int row, int span) {
        node.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(node, Priority.ALWAYS);
        grid.add(node, col, row, span, 1);
    }

    private VBox buildHero() {
        statusText.getStyleClass().add("status-chip");
        HBox statusRow = Ui.row(8, dot, statusText, modulation, Ui.hSpacer(), updated);

        headline.getStyleClass().add("health-headline");
        headline.setWrapText(true);
        detail.getStyleClass().add("health-detail");
        detail.setWrapText(true);

        VBox left = new VBox(6, headline, detail);
        VBox.setVgrow(left, Priority.ALWAYS);

        grade.getStyleClass().add("health-grade");
        scoreText.getStyleClass().add("health-score");
        VBox right = new VBox(0, grade, scoreText);
        right.setAlignment(Pos.CENTER);
        right.setMinWidth(78);

        HBox body = new HBox(18, left, right);
        HBox.setHgrow(left, Priority.ALWAYS);
        body.setAlignment(Pos.CENTER_LEFT);

        return Ui.hero(12, statusRow, body, lastEvent);
    }

    private VBox buildUptimeCard() {
        uptimePct.getStyleClass().add("metric-value");
        strip.setOnHover(text -> uptimeInfo.setText(text == null ? summaryText : text));
        return Ui.card(9, Ui.cardTitle("SON 24 SAAT"),
                Ui.row(7, uptimePct, Ui.faint("çalışma oranı")),
                strip, uptimeInfo, uninterrupted);
    }

    private VBox buildLiveCard() {
        liveChart.setPrefHeight(168);
        liveChart.setSeries(List.of());
        liveLegend = Ui.row(14, legendItem("Aşağı yön", Role.DOWN), legendItem("Yukarı yön", Role.UP));
        VBox card = Ui.card(10,
                Ui.row(10, liveTitle, Ui.hSpacer(), liveLegend),
                liveChart);
        VBox.setVgrow(liveChart, Priority.ALWAYS);
        return card;
    }

    /**
     * Kartlari moda gore yerlestirir. Modem izlenmiyorken DSL kartlari hic
     * eklenmez: surekli "—" gosteren kartlar veri yokmus gibi degil, olcum
     * bozulmus gibi duruyor.
     */
    private void rebuildLayout() {
        bento.getChildren().clear();
        add(bento, heroCard, 0, 0, 2);
        add(bento, uptimeCard, 2, 0, 1);
        add(bento, liveCard, 0, 1, 3);
        if (modemMonitored) {
            add(bento, snrCard, 0, 2, 1);
            add(bento, syncCard, 1, 2, 1);
            add(bento, crcCard, 2, 2, 1);
            add(bento, attenCard, 0, 3, 1);
            add(bento, capacityCard, 1, 3, 1);
            add(bento, todayCard, 2, 3, 1);
            add(bento, latencyCard, 0, 4, 1);
            add(bento, jitterCard, 1, 4, 1);
            add(bento, lossCard, 2, 4, 1);
        } else {
            add(bento, latencyCard, 0, 2, 1);
            add(bento, jitterCard, 1, 2, 1);
            add(bento, lossCard, 2, 2, 1);
        }

        liveTitle.setText(modemMonitored ? "SON 60 DAKİKA · SNR MARJI" : "SON 60 DAKİKA · GECİKME");
        liveChart.unit(modemMonitored ? "dB" : "ms");
        liveLegend.setVisible(modemMonitored);
        liveLegend.setManaged(modemMonitored);
        uptimeInfo.setText(summaryText);
    }

    /**
     * Modem izlemesinin acik olup olmadigini bildirir; Pano yerlesimi buna gore
     * degisir. JavaFX is parcaciginda cagrilmalidir.
     */
    public void setModemMonitored(boolean on) {
        if (modemMonitored == on) return;
        modemMonitored = on;
        rebuildLayout();
        if (!on) {
            // DSL'e ozgu metinler modemsiz modda anlamsiz kaliyor
            statusText.setText("İNTERNET İZLENİYOR");
            statusText.getStyleClass().removeAll("up", "down", "warn");
            modulation.setText("modem yapılandırılmadı");
            headline.setText("Modem izlenmiyor");
            detail.setText("Ayarlar'dan modem şifresini girersen hat kalitesi (SNR, senkron hızı, "
                    + "CRC) de izlenir. Şu an yalnızca internet erişimi ölçülüyor.");
            grade.setText("—");
            grade.setStyle("");
            scoreText.setText("");
        }
    }

    private HBox legendItem(String text, Role role) {
        Circle marker = new Circle(4);
        marker.setFill(role == Role.DOWN ? Theme.palette().down() : Theme.palette().up());
        Theme.paletteProperty().addListener((obs, old, now) ->
                marker.setFill(role == Role.DOWN ? now.down() : now.up()));
        return Ui.row(6, marker, Ui.faint(text));
    }

    // ---------- guncelleme ----------

    /** Herhangi bir thread'den cagrilabilir. */
    public void update(LineSample s) {
        LineSample prev = previous;
        previous = s;
        lastSampleTs = s.ts();

        Platform.runLater(() -> {
            boolean up = s.isUp();
            Ui.setDotKind(dot, up ? "up" : "down");
            statusText.setText(up ? "HAT AKTİF" : "HAT KOPUK");
            statusText.getStyleClass().removeAll("up", "down", "warn");
            statusText.getStyleClass().add(up ? "up" : "down");
            modulation.setText(s.modulation() + " · " + s.status());
            updated.setText("son güncelleme " + Fmt.ago(s.ts()));
            startPulse(up);

            double snrDown = s.snrDown() / 10.0;
            snrCard.setValue(snrDown, Fmt::dec1);
            snrCard.setUnit("dB");
            snrCard.setSub("yukarı yön " + Fmt.dec1(s.snrUp() / 10.0) + " dB");
            snrCard.setState(snrDown < 6 ? "bad" : snrDown < 9 ? "warn" : "good");

            syncCard.setValue(s.syncDown() / 1000.0, Fmt::dec1);
            syncCard.setUnit("Mbps");
            syncCard.setSub("yukarı yön " + Fmt.dec1(s.syncUp() / 1000.0) + " Mbps");

            crcCard.setValue(s.crcDown(), v -> Fmt.count(Math.round(v)));
            crcCard.setSub("kümülatif · yukarı yön " + Fmt.count(s.crcUp()));
            if (prev != null && s.crcDown() > prev.crcDown()) {
                crcCard.setDelta(Fmt.signed(s.crcDown() - prev.crcDown()), "down");
            } else if (prev != null) {
                crcCard.setDelta("sabit", null);
            }

            attenCard.setValue(Fmt.pair1(s.attenDown() / 10.0, s.attenUp() / 10.0));
            attenCard.setUnit("dB");
            attenCard.setSub("çıkış gücü " + Fmt.pair1(s.powerDown() / 10.0, s.powerUp() / 10.0) + " dBm");

            double ratio = s.maxRateDown() > 0 ? 100.0 * s.syncDown() / s.maxRateDown() : 0;
            capacityCard.setValue(ratio, Fmt::pct0);
            capacityCard.setSub(Fmt.dec1(s.maxRateDown() / 1000.0) + " Mbps kapasitenin kullanımı");
            capacityCard.setState(ratio < 60 ? "warn" : null);
        });

        refreshBackground(s);
    }

    private void startPulse(boolean up) {
        if (pulse != null) pulse.stop();
        dot.setOpacity(1);
        if (up) {
            pulse = Anim.pulse(dot);
            pulse.play();
        }
    }

    /** Toplayici hata verdiginde hero bandini uyariya cevirir. */
    public void showError(String message) {
        Platform.runLater(() -> {
            Ui.setDotKind(dot, "down");
            statusText.setText("BAĞLANTI SORUNU");
            statusText.getStyleClass().removeAll("up", "down", "warn");
            statusText.getStyleClass().add("down");
            if (pulse != null) pulse.stop();
            dot.setOpacity(1);
            headline.setText("Modeme ulaşılamıyor");
            detail.setText(message == null ? "" : message);
            grade.setText("—");
            grade.setStyle("");
            scoreText.setText("");
        });
    }

    private void refreshBackground(LineSample s) {
        worker.execute(() -> {
            try {
                long now = System.currentTimeMillis();
                long dayAgo = now - DAY_MS;
                long hourAgo = now - HOUR_MS;
                long midnight = LocalDate.now().atStartOfDay(ZoneId.systemDefault())
                        .toInstant().toEpochMilli();

                List<LineSample> lastHour = samples.findRange(hourAgo, now, 180);
                List<long[]> statuses = samples.findStatusSince(dayAgo);
                List<long[]> crcPoints = samples.findCrcPointsSince(dayAgo);
                int dropsToday = events.countSince(midnight, LineEvent.Type.LINK_DOWN);
                int drops24h = events.countSince(dayAgo, LineEvent.Type.LINK_DOWN);
                long lastDownTs = events.lastTs(LineEvent.Type.LINK_DOWN);
                List<LineEvent> recent = events.findRecent(1);

                long crcToday = crcSum(crcPoints, midnight);
                double crcPerHour = crcRate(crcPoints);
                int[] states = buildStrip(statuses, dayAgo);
                double uptime = uptimePercent(states);

                HealthScore health = s.isUp()
                        ? HealthScore.of(s, drops24h, crcPerHour)
                        : HealthScore.down(s.status());

                Series series = buildSeries(lastHour);

                Platform.runLater(() -> {
                    applyHealth(health);
                    strip.setData(states, dayAgo, DAY_MS / STRIP_BLOCKS);
                    uptimePct.setText(Fmt.pct1(uptime));
                    summaryText = drops24h == 0
                            ? "son 24 saatte kopma yok"
                            : "son 24 saatte " + drops24h + " kopma";
                    uptimeInfo.setText(summaryText);
                    uninterrupted.setText(lastDownTs > 0
                            ? "kesintisiz: " + Fmt.uptime(now - lastDownTs)
                            : "kayıtlarda kopma yok");

                    liveChart.setRange(hourAgo, now);
                    liveChart.setSeries(List.of(
                            new TimeChart.Series("Aşağı", Role.DOWN, series.ts(), series.snrDown(), true),
                            new TimeChart.Series("Yukarı", Role.UP, series.ts(), series.snrUp(), false)));

                    if (snrCard.sparkline() != null) snrCard.sparkline().setValues(series.snrDown());
                    if (syncCard.sparkline() != null) syncCard.sparkline().role(Role.DOWN)
                            .setValues(series.syncDown());
                    if (crcCard.sparkline() != null) crcCard.sparkline().role(Role.WARN)
                            .setValues(series.crcDelta());
                    if (attenCard.sparkline() != null) attenCard.sparkline().role(Role.MUTED)
                            .setValues(series.attenDown());
                    if (capacityCard.sparkline() != null) capacityCard.sparkline().role(Role.ACCENT)
                            .setValues(series.capacity());

                    todayCard.setValue(dropsToday, v -> Fmt.count(Math.round(v)));
                    todayCard.setUnit("kopma");
                    todayCard.setState(dropsToday > 0 ? "bad" : "good");
                    todayCard.setSub("gece yarısından beri " + Fmt.count(crcToday) + " CRC artışı");

                    if (!recent.isEmpty()) {
                        LineEvent e = recent.get(0);
                        lastEvent.setText("Son olay: " + e.type().label + " · " + Fmt.dateTime(e.ts()));
                    } else {
                        lastEvent.setText("Henüz kayıtlı olay yok.");
                    }
                });
            } catch (Exception e) {
                System.err.println("Pano verisi yüklenemedi: " + e.getMessage());
            }
        });
    }

    private void applyHealth(HealthScore health) {
        headline.setText(health.headline());
        detail.setText(health.detail());
        grade.setText(health.grade());
        scoreText.setText(health.score() > 0 ? health.score() + " / 100" : "");
        Theme.Palette p = Theme.palette();
        javafx.scene.paint.Color c = switch (health.state()) {
            case "up" -> p.good();
            case "warn" -> p.warn();
            case "down" -> p.bad();
            default -> p.textFaint();
        };
        grade.setStyle("-fx-text-fill: " + toHex(c) + ";");
    }

    private static String toHex(javafx.scene.paint.Color c) {
        return String.format("#%02x%02x%02x",
                (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
    }

    /** Son 24 saati 15 dakikalik bloklara boler. */
    private static int[] buildStrip(List<long[]> statuses, long from) {
        long blockMs = DAY_MS / STRIP_BLOCKS;
        int[] states = new int[STRIP_BLOCKS];
        for (long[] point : statuses) {
            int idx = (int) ((point[0] - from) / blockMs);
            if (idx < 0 || idx >= STRIP_BLOCKS) continue;
            if (point[1] == 0) states[idx] = UptimeStrip.DOWN;
            else if (states[idx] == UptimeStrip.UNKNOWN) states[idx] = UptimeStrip.UP;
        }
        return states;
    }

    private static double uptimePercent(int[] states) {
        long known = 0;
        long down = 0;
        for (int state : states) {
            if (state == UptimeStrip.UNKNOWN) continue;
            known++;
            if (state == UptimeStrip.DOWN) down++;
        }
        return known > 0 ? 100.0 * (known - down) / known : 0;
    }

    /**
     * {@code from}'dan bu yana toplam CRC artisi.
     *
     * <p>Basit "son deger - ilk deger" cikarmasi yapilamaz: hat her koptugunda modem
     * sayaci sifirliyor, o zaman fark negatif cikip 0 gorunuyordu. Bunun yerine
     * pozitif artislar toplaniyor; {@code from} oncesindeki son deger baslangic
     * noktasi olarak tasiniyor ki ilk araliktaki artis da sayilsin.
     */
    private static long crcSum(List<long[]> points, long from) {
        long total = 0;
        long prev = -1;
        for (long[] point : points) {
            if (point[0] < from) {
                prev = point[1];
                continue;
            }
            if (prev >= 0 && point[1] >= prev) total += point[1] - prev;
            prev = point[1];
        }
        return total;
    }

    /** (ts, kumulatif crc) noktalarindan saatlik ortalama artis. */
    private static double crcRate(List<long[]> points) {
        if (points.size() < 2) return 0;
        long total = 0;
        long prev = -1;
        for (long[] point : points) {
            if (prev >= 0 && point[1] >= prev) total += point[1] - prev;
            prev = point[1];
        }
        double hours = Math.max(0.25,
                (points.get(points.size() - 1)[0] - points.get(0)[0]) / 3_600_000.0);
        return total / hours;
    }

    private record Series(long[] ts, double[] snrDown, double[] snrUp, double[] syncDown,
                          double[] crcDelta, double[] attenDown, double[] capacity) {
    }

    private static Series buildSeries(List<LineSample> rows) {
        int n = rows.size();
        long[] ts = new long[n];
        double[] snrDown = new double[n];
        double[] snrUp = new double[n];
        double[] syncDown = new double[n];
        double[] crcDelta = new double[n];
        double[] attenDown = new double[n];
        double[] capacity = new double[n];
        long prevCrc = -1;
        for (int i = 0; i < n; i++) {
            LineSample s = rows.get(i);
            ts[i] = s.ts();
            snrDown[i] = s.snrDown() / 10.0;
            snrUp[i] = s.snrUp() / 10.0;
            syncDown[i] = s.syncDown() / 1000.0;
            crcDelta[i] = prevCrc >= 0 ? Math.max(0, s.crcDown() - prevCrc) : 0;
            attenDown[i] = s.attenDown() / 10.0;
            capacity[i] = s.maxRateDown() > 0 ? 100.0 * s.syncDown() / s.maxRateDown() : 0;
            prevCrc = s.crcDown();
        }
        return new Series(ts, snrDown, snrUp, syncDown, crcDelta, attenDown, capacity);
    }

    /** Ayarlardan yeni esik/sifre uygulandiginda listeler yenilensin diye. */
    public void refresh() {
        LineSample s = previous;
        if (s != null) refreshBackground(s);
    }

    // ---------- internet erisim olcumu ----------

    /** Herhangi bir thread'den cagrilabilir. */
    public void updateNet(NetSample n) {
        lastNetSample = n;
        Platform.runLater(() -> applyNetCards(n));
        refreshNetBackground();
    }

    private void applyNetCards(NetSample n) {
        int warnMs = settings.latencyThresholdMs();
        if (n.online() && n.hasLatency()) {
            latencyCard.setValue(n.latencyMs(), Fmt::dec0);
            latencyCard.setUnit("ms");
            latencyCard.setState(n.latencyMs() > warnMs ? "bad"
                    : n.latencyMs() > warnMs * 0.6 ? "warn" : "good");
            latencyCard.setSub(n.target().isBlank() ? "TCP yoklaması" : n.target() + " · TCP yoklaması");
        } else {
            latencyCard.setValue("—");
            latencyCard.setUnit("");
            latencyCard.setState("bad");
            latencyCard.setSub("erişim yok");
        }

        if (n.hasJitter()) {
            // Tek tur icindeki sapma tipik olarak 1 ms'in altinda kaliyor;
            // tam sayiya yuvarlanirsa kart surekli "0" gosterip degersiz gorunuyor.
            jitterCard.setValue(n.jitterMs(), Fmt::dec1);
            jitterCard.setUnit("ms");
            jitterCard.setState(n.jitterMs() > 30 ? "bad" : n.jitterMs() > 10 ? "warn" : "good");
            // Alt yazi arka planda son 1 saatin gecikme araligiyla doldurulur.
        } else {
            jitterCard.setValue("—");
            jitterCard.setUnit("");
            jitterCard.setState(null);
            jitterCard.setSub(n.online() ? "yedek hedef · hesaplanamadı" : "erişim yok");
        }

        lossCard.setValue(n.lossPct(), Fmt::pct0);
        lossCard.setState(n.lossPct() >= settings.lossThresholdPct() ? "bad"
                : n.lossPct() > 0 ? "warn" : "good");
        lossCard.setSub(n.received() + "/" + n.sent() + " yoklama yanıtlandı");
    }

    private void refreshNetBackground() {
        worker.execute(() -> {
            try {
                long now = System.currentTimeMillis();
                long hourAgo = now - HOUR_MS;
                long dayAgo = now - DAY_MS;

                List<NetSample> lastHour = netSamples.findRange(hourAgo, now, 180);
                NetSampleRepository.Summary day = netSamples.summarySince(dayAgo);
                // Serit ve buyuk grafik yalnizca modemsiz modda internete ayrilir
                int[] netStates = modemMonitored ? null
                        : buildStrip(netSamples.findStatusSince(dayAgo), dayAgo);

                int n = lastHour.size();
                long[] ts = new long[n];
                double[] latency = new double[n];
                double[] jitter = new double[n];
                double[] loss = new double[n];
                double minLatency = Double.MAX_VALUE;
                double maxLatency = -1;
                for (int i = 0; i < n; i++) {
                    NetSample s = lastHour.get(i);
                    ts[i] = s.ts();
                    latency[i] = Math.max(0, s.latencyMs());
                    jitter[i] = Math.max(0, s.jitterMs());
                    loss[i] = s.lossPct();
                    if (s.latencyMs() >= 0) {
                        minLatency = Math.min(minLatency, s.latencyMs());
                        maxLatency = Math.max(maxLatency, s.latencyMs());
                    }
                }
                // Tur ici jitter cok dar bir pencereyi olcuyor; baglantinin gercekte
                // ne kadar kararli oldugunu son bir saatin gecikme araligi gosteriyor.
                String latencyRange = maxLatency >= 0
                        ? "son 1 saat: " + Fmt.dec0(minLatency) + "–" + Fmt.dec0(maxLatency) + " ms"
                        : "";

                Platform.runLater(() -> {
                    if (latencyCard.sparkline() != null) {
                        latencyCard.sparkline().role(Role.ACCENT).setValues(latency);
                    }
                    if (jitterCard.sparkline() != null) {
                        jitterCard.sparkline().role(Role.MUTED).setValues(jitter);
                    }
                    if (!latencyRange.isEmpty() && lastNetSample != null && lastNetSample.hasJitter()) {
                        jitterCard.setSub(latencyRange);
                    }
                    if (lossCard.sparkline() != null) {
                        lossCard.sparkline().role(Role.WARN).setValues(loss);
                    }
                    if (modemMonitored) return;

                    liveChart.setRange(hourAgo, now);
                    liveChart.setSeries(List.of(
                            new TimeChart.Series("Gecikme", Role.ACCENT, ts, latency, true)));
                    if (netStates != null) {
                        strip.setData(netStates, dayAgo, DAY_MS / STRIP_BLOCKS);
                        uptimePct.setText(Fmt.pct1(day.onlinePct()));
                    }
                    summaryText = day.hasData() && day.avgLatencyMs() >= 0
                            ? "ortalama gecikme " + Fmt.dec0(day.avgLatencyMs()) + " ms"
                            : "ölçümler toplanıyor";
                    uptimeInfo.setText(summaryText);
                    uninterrupted.setText(day.hasData()
                            ? day.samples() + " ölçüm · kayıp " + Fmt.pct0(day.avgLossPct())
                            : "");
                    applyNetHealth(day);
                });
            } catch (Exception e) {
                System.err.println("Internet olcum verisi yuklenemedi: " + e.getMessage());
            }
        });
    }

    /** Modemsiz modda hero bandini internet ozetiyle doldurur. */
    private void applyNetHealth(NetSampleRepository.Summary day) {
        NetSample now = lastNetSample;
        if (now == null) return;
        boolean online = now.online();
        boolean slow = online && ((now.hasLatency() && now.latencyMs() > settings.latencyThresholdMs())
                || now.lossPct() >= settings.lossThresholdPct());
        String state = !online ? "down" : slow ? "warn" : "up";

        Ui.setDotKind(dot, state);
        startPulse(online);
        statusText.setText(!online ? "İNTERNET YOK" : slow ? "BAĞLANTI ZAYIF" : "İNTERNET VAR");
        statusText.getStyleClass().removeAll("up", "down", "warn");
        statusText.getStyleClass().add(state);
        modulation.setText(now.target().isBlank()
                ? "modem izlenmiyor"
                : now.target() + " · modem izlenmiyor");
        updated.setText("son güncelleme " + Fmt.ago(now.ts()));

        headline.setText(!online ? "İnternet erişimi yok"
                : slow ? "Bağlantı yavaş" : "İnternet erişimi sorunsuz");

        StringBuilder text = new StringBuilder();
        if (day.hasData()) {
            text.append("Son 24 saatte ").append(Fmt.pct1(day.onlinePct())).append(" erişim");
            if (day.avgLatencyMs() >= 0) {
                text.append(", ortalama gecikme ").append(Fmt.dec0(day.avgLatencyMs())).append(" ms");
            }
            if (day.avgLossPct() > 0) {
                text.append(", paket kaybı ").append(Fmt.pct0(day.avgLossPct()));
            }
            text.append(". ");
        } else {
            text.append("Ölçümler toplanıyor. ");
        }
        text.append("Hat kalitesini (SNR, senkron hızı, CRC) de izlemek için "
                + "Ayarlar'dan modem şifreni gir.");
        detail.setText(text.toString());

        grade.setText(now.hasLatency() ? Fmt.dec0(now.latencyMs()) : "—");
        scoreText.setText(now.hasLatency() ? "ms gecikme" : "");
        Theme.Palette p = Theme.palette();
        grade.setStyle("-fx-text-fill: "
                + toHex(!online ? p.bad() : slow ? p.warn() : p.good()) + ";");
    }
}
