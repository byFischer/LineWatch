package com.emre.linewatch.ui;

import com.emre.linewatch.collector.LineSample;
import com.emre.linewatch.storage.EventRepository;
import com.emre.linewatch.storage.LineEvent;
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

    private final SampleRepository samples;
    private final EventRepository events;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dashboard-stats");
        t.setDaemon(true);
        return t;
    });

    private LineSample previous;
    private volatile long lastSampleTs;
    private Timeline pulse;
    /** Serit uzerinde fare yokken alt satirda gosterilen ozet. */
    private String summaryText = "son 24 saat";

    public DashboardView(SampleRepository samples, EventRepository events) {
        this.samples = samples;
        this.events = events;

        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);

        GridPane bento = new GridPane();
        bento.setHgap(14);
        bento.setVgap(14);
        for (int i = 0; i < 3; i++) {
            ColumnConstraints c = new ColumnConstraints();
            c.setPercentWidth(100.0 / 3);
            bento.getColumnConstraints().add(c);
        }

        VBox hero = buildHero();
        VBox uptime = buildUptimeCard();
        VBox live = buildLiveCard();

        add(bento, hero, 0, 0, 2);
        add(bento, uptime, 2, 0, 1);
        add(bento, live, 0, 1, 3);
        add(bento, snrCard, 0, 2, 1);
        add(bento, syncCard, 1, 2, 1);
        add(bento, crcCard, 2, 2, 1);
        add(bento, attenCard, 0, 3, 1);
        add(bento, capacityCard, 1, 3, 1);
        add(bento, todayCard, 2, 3, 1);

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
        HBox legend = Ui.row(14, legendItem("Aşağı yön", Role.DOWN), legendItem("Yukarı yön", Role.UP));
        VBox card = Ui.card(10,
                Ui.row(10, Ui.cardTitle("SON 60 DAKİKA · SNR MARJI"), Ui.hSpacer(), legend),
                liveChart);
        VBox.setVgrow(liveChart, Priority.ALWAYS);
        return card;
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
}
