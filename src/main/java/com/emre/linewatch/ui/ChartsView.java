package com.emre.linewatch.ui;

import com.emre.linewatch.collector.LineSample;
import com.emre.linewatch.config.Settings;
import com.emre.linewatch.storage.EventRepository;
import com.emre.linewatch.storage.LineEvent;
import com.emre.linewatch.storage.SampleRepository;
import com.emre.linewatch.ui.widget.EventLane;
import com.emre.linewatch.ui.widget.Role;
import com.emre.linewatch.ui.widget.Segmented;
import com.emre.linewatch.ui.widget.TimeChart;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ust uste uc grafik: SNR marji, senkron hizi, CRC artisi.
 * Ortak zaman ekseni ve ortak crosshair — farenin oldugu an ucunde birden okunur.
 * En altta ayni eksende olay seridi.
 */
public class ChartsView extends VBox {

    private static final int MAX_POINTS = 320;

    private final SampleRepository samples;
    private final EventRepository events;
    private final Settings settings;

    private final Segmented range = new Segmented("1 saat", "24 saat", "7 gün", "30 gün");
    private final Label hint = Ui.faint("Grafiğin üzerine gel: değerler birlikte okunur");

    private final TimeChart snrChart = new TimeChart().unit("dB");
    private final TimeChart syncChart = new TimeChart().unit("Mbps");
    private final TimeChart crcChart = new TimeChart().unit("hata").zeroBased(true).timeAxis(true);
    private final EventLane lane = new EventLane();
    private final List<TimeChart> charts = List.of(snrChart, syncChart, crcChart);

    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "chart-loader");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean reloadQueued = new AtomicBoolean();

    public ChartsView(SampleRepository samples, EventRepository events, Settings settings) {
        this.samples = samples;
        this.events = events;
        this.settings = settings;

        getStyleClass().add("page");
        setSpacing(14);

        range.setOnSelect(i -> reload());
        HBox toolbar = Ui.row(14, Ui.cardTitle("ARALIK"), range, Ui.hSpacer(), hint);

        snrChart.setPrefHeight(150);
        syncChart.setPrefHeight(130);
        crcChart.setPrefHeight(140);

        VBox stack = Ui.card(6,
                header("SNR MARJI", "dB", true),
                snrChart,
                header("SENKRON HIZI", "Mbps", true),
                syncChart,
                header("CRC HATA ARTIŞI", "aralık başına artış", false),
                crcChart,
                lane);
        VBox.setVgrow(snrChart, Priority.ALWAYS);
        VBox.setVgrow(syncChart, Priority.ALWAYS);
        VBox.setVgrow(crcChart, Priority.ALWAYS);
        VBox.setVgrow(stack, Priority.ALWAYS);

        getChildren().addAll(toolbar, stack);

        wireCrosshair();
        lane.setOnHover(text -> hint.setText(text == null
                ? "Grafiğin üzerine gel: değerler birlikte okunur" : text));

        reload();
    }

    private HBox header(String title, String unit, boolean withLegend) {
        HBox box = Ui.row(10, Ui.cardTitle(title), Ui.faint(unit));
        if (withLegend) {
            box.getChildren().addAll(Ui.hSpacer(),
                    legendItem("Aşağı yön", true), legendItem("Yukarı yön", false));
            box.setSpacing(10);
        }
        VBox.setMargin(box, new javafx.geometry.Insets(6, 0, 0, 0));
        return box;
    }

    private HBox legendItem(String text, boolean downSeries) {
        Circle marker = new Circle(4);
        marker.setFill(downSeries ? Theme.palette().down() : Theme.palette().up());
        Theme.paletteProperty().addListener((obs, old, now) ->
                marker.setFill(downSeries ? now.down() : now.up()));
        return Ui.row(6, marker, Ui.faint(text));
    }

    /** Bir grafikte fare hareket edince digerleri ve olay seridi ayni ana kilitlenir. */
    private void wireCrosshair() {
        for (TimeChart source : charts) {
            source.setOnCrosshair(t -> {
                for (TimeChart other : charts) {
                    if (other != source) other.setCrosshair(t);
                }
                lane.setCrosshair(t);
            });
        }
        lane.setOnCrosshair(t -> charts.forEach(c -> c.setCrosshair(t)));
    }

    /** Canli modda yeni ornek gelince grafigi tazeler (yalnizca 1 saat araliginda). */
    public void onSample(LineSample s) {
        if (range.selected() != 0) return;
        if (reloadQueued.compareAndSet(false, true)) {
            Platform.runLater(() -> {
                reloadQueued.set(false);
                reload();
            });
        }
    }

    private void reload() {
        int idx = range.selected();
        long now = System.currentTimeMillis();
        long from = switch (idx) {
            case 1 -> now - 24L * 3600_000;
            case 2 -> now - 7L * 24 * 3600_000;
            case 3 -> now - 30L * 24 * 3600_000;
            default -> now - 3600_000L;
        };

        loader.execute(() -> {
            try {
                List<LineSample> rows = samples.findRange(from, now, MAX_POINTS);
                List<LineEvent> eventList = events.findRange(from, now);
                double snrThreshold = settings.snrThresholdDb();

                int n = rows.size();
                long[] ts = new long[n];
                double[] snrDown = new double[n];
                double[] snrUp = new double[n];
                double[] syncDown = new double[n];
                double[] syncUp = new double[n];
                double[] crcDelta = new double[n];
                long prevCrc = -1;
                for (int i = 0; i < n; i++) {
                    LineSample s = rows.get(i);
                    ts[i] = s.ts();
                    snrDown[i] = s.snrDown() / 10.0;
                    snrUp[i] = s.snrUp() / 10.0;
                    syncDown[i] = s.syncDown() / 1000.0;
                    syncUp[i] = s.syncUp() / 1000.0;
                    crcDelta[i] = prevCrc >= 0 ? Math.max(0, s.crcDown() - prevCrc) : 0;
                    prevCrc = s.crcDown();
                }

                List<TimeChart.Marker> markers = new ArrayList<>();
                for (LineEvent e : eventList) {
                    Role role = switch (Icons.severity(e.type())) {
                        case "bad" -> Role.BAD;
                        case "warn" -> Role.WARN;
                        case "good" -> Role.GOOD;
                        default -> Role.ACCENT;
                    };
                    markers.add(new TimeChart.Marker(e.ts(), e.type().label, role));
                }

                Platform.runLater(() -> {
                    for (TimeChart c : charts) c.setRange(from, now);
                    lane.setRange(from, now);

                    snrChart.threshold(snrThreshold, "uyarı eşiği " + Fmt.dec1(snrThreshold) + " dB");
                    snrChart.setSeries(List.of(
                            new TimeChart.Series("Aşağı", Role.DOWN, ts, snrDown, true),
                            new TimeChart.Series("Yukarı", Role.UP, ts, snrUp, false)));
                    syncChart.setSeries(List.of(
                            new TimeChart.Series("Aşağı", Role.DOWN, ts, syncDown, true),
                            new TimeChart.Series("Yukarı", Role.UP, ts, syncUp, false)));
                    crcChart.setSeries(List.of(
                            new TimeChart.Series("CRC artışı", Role.WARN, ts, crcDelta, true)));

                    // olay isaretleri yalnizca kopmalar; her seyi cizmek grafigi kirletiyor
                    List<TimeChart.Marker> lineMarks = markers.stream()
                            .filter(m -> m.role() == Role.BAD)
                            .toList();
                    for (TimeChart c : charts) c.setMarkers(lineMarks);
                    lane.setMarks(markers);
                });
            } catch (Exception e) {
                System.err.println("Grafik verisi yüklenemedi: " + e.getMessage());
            }
        });
    }
}
