package com.emre.linewatch.ui;

import com.emre.linewatch.collector.LineSample;
import com.emre.linewatch.collector.NetSample;
import com.emre.linewatch.config.I18n;
import com.emre.linewatch.config.Log;
import com.emre.linewatch.config.Settings;
import com.emre.linewatch.storage.EventRepository;
import com.emre.linewatch.storage.LineEvent;
import com.emre.linewatch.storage.NetSampleRepository;
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
import java.util.logging.Level;

/**
 * Ust uste uc grafik: SNR marji, senkron hizi, CRC artisi.
 * Ortak zaman ekseni ve ortak crosshair — farenin oldugu an ucunde birden okunur.
 * En altta ayni eksende olay seridi.
 */
public class ChartsView extends VBox {

    private static final int MAX_POINTS = 320;

    private final SampleRepository samples;
    private final NetSampleRepository netSamples;
    private final EventRepository events;
    private final Settings settings;

    private final Segmented range = new Segmented(I18n.t("chart.range1h"), I18n.t("chart.range24h"),
            I18n.t("chart.range7d"), I18n.t("chart.range30d"));
    private final Label hint = Ui.faint(I18n.t("chart.hint"));

    private final TimeChart snrChart = new TimeChart().unit("dB");
    private final TimeChart syncChart = new TimeChart().unit("Mbps");
    private final TimeChart crcChart = new TimeChart().unit(I18n.t("chart.unitErrors")).zeroBased(true).timeAxis(true);

    // Modem izlenmiyorken ayni yerlesim internet olcumleriyle doldurulur
    private final TimeChart latencyChart = new TimeChart().unit("ms").zeroBased(true);
    private final TimeChart jitterChart = new TimeChart().unit("ms").zeroBased(true);
    private final TimeChart lossChart = new TimeChart().unit("%").zeroBased(true).timeAxis(true);

    private final EventLane lane = new EventLane();
    private final List<TimeChart> dslCharts = List.of(snrChart, syncChart, crcChart);
    private final List<TimeChart> netCharts = List.of(latencyChart, jitterChart, lossChart);
    private List<TimeChart> charts = dslCharts;
    private VBox stack;
    private boolean modemMonitored = true;

    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "chart-loader");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean reloadQueued = new AtomicBoolean();

    public ChartsView(SampleRepository samples, NetSampleRepository netSamples,
                      EventRepository events, Settings settings) {
        this.samples = samples;
        this.netSamples = netSamples;
        this.events = events;
        this.settings = settings;

        getStyleClass().add("page");
        setSpacing(14);

        range.setOnSelect(i -> reload());
        HBox toolbar = Ui.row(14, Ui.cardTitle(I18n.t("chart.range")), range, Ui.hSpacer(), hint);

        snrChart.setPrefHeight(150);
        syncChart.setPrefHeight(130);
        crcChart.setPrefHeight(140);
        latencyChart.setPrefHeight(150);
        jitterChart.setPrefHeight(130);
        lossChart.setPrefHeight(140);

        stack = Ui.card(6);
        VBox.setVgrow(stack, Priority.ALWAYS);
        getChildren().addAll(toolbar, stack);

        lane.setOnHover(text -> hint.setText(text == null ? I18n.t("chart.hint") : text));

        rebuildStack();
    }

    /**
     * Modem izlemesinin acik olup olmadigini bildirir; grafik yigini buna gore
     * DSL ya da internet olcumlerine gecer. JavaFX is parcaciginda cagrilmalidir.
     */
    public void setModemMonitored(boolean on) {
        if (modemMonitored == on) return;
        modemMonitored = on;
        rebuildStack();
    }

    private void rebuildStack() {
        charts = modemMonitored ? dslCharts : netCharts;
        stack.getChildren().setAll(modemMonitored
                ? List.of(header(I18n.t("dash.snr"), "dB", true), snrChart,
                        header(I18n.t("dash.sync"), "Mbps", true), syncChart,
                        header(I18n.t("chart.crcDelta"), I18n.t("chart.perInterval"), false), crcChart, lane)
                : List.of(header(I18n.t("dash.latency"), "ms", false), latencyChart,
                        header(I18n.t("dash.jitter"), I18n.t("chart.jitterUnit"), false), jitterChart,
                        header(I18n.t("dash.loss"), "%", false), lossChart, lane));
        for (TimeChart c : charts) VBox.setVgrow(c, Priority.ALWAYS);
        wireCrosshair();
        reload();
    }

    private HBox header(String title, String unit, boolean withLegend) {
        HBox box = Ui.row(10, Ui.cardTitle(title), Ui.faint(unit));
        if (withLegend) {
            box.getChildren().addAll(Ui.hSpacer(),
                    legendItem(I18n.t("common.down"), true), legendItem(I18n.t("common.up"), false));
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

    /** Canli modda yeni DSL ornegi gelince grafigi tazeler (yalnizca 1 saat araliginda). */
    public void onSample(LineSample s) {
        if (modemMonitored) queueReload();
    }

    /** Modem izlenmiyorken canli tazeleme internet olcumune bagli. */
    public void onNet(NetSample n) {
        if (!modemMonitored) queueReload();
    }

    private void queueReload() {
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
        boolean modem = modemMonitored;

        loader.execute(() -> {
            try {
                List<LineEvent> eventList = events.findRange(from, now);
                if (modem) loadDsl(from, now, eventList);
                else loadNet(from, now, eventList);
            } catch (Exception e) {
                Log.get(ChartsView.class).log(Level.WARNING, "Grafik verisi yüklenemedi", e);
            }
        });
    }

    private void loadDsl(long from, long now, List<LineEvent> eventList) throws Exception {
        List<LineSample> rows = samples.findRange(from, now, MAX_POINTS);
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

        List<TimeChart.Marker> markers = buildMarkers(eventList);

        Platform.runLater(() -> {
            applyRange(from, now);
            snrChart.threshold(snrThreshold, I18n.t("chart.thresholdDb", Fmt.dec1(snrThreshold)));
            snrChart.setSeries(List.of(
                    new TimeChart.Series(I18n.t("common.downShort"), Role.DOWN, ts, snrDown, true),
                    new TimeChart.Series(I18n.t("common.upShort"), Role.UP, ts, snrUp, false)));
            syncChart.setSeries(List.of(
                    new TimeChart.Series(I18n.t("common.downShort"), Role.DOWN, ts, syncDown, true),
                    new TimeChart.Series(I18n.t("common.upShort"), Role.UP, ts, syncUp, false)));
            crcChart.setSeries(List.of(
                    new TimeChart.Series(I18n.t("chart.seriesCrc"), Role.WARN, ts, crcDelta, true)));
            applyMarkers(markers);
        });
    }

    private void loadNet(long from, long now, List<LineEvent> eventList) throws Exception {
        List<NetSample> rows = netSamples.findRange(from, now, MAX_POINTS);

        int n = rows.size();
        long[] ts = new long[n];
        double[] latency = new double[n];
        double[] jitter = new double[n];
        double[] loss = new double[n];
        for (int i = 0; i < n; i++) {
            NetSample s = rows.get(i);
            ts[i] = s.ts();
            // Olculemeyen degerler -1 saklanir; grafikte 0'a cekilmezse eksen bozulur
            latency[i] = Math.max(0, s.latencyMs());
            jitter[i] = Math.max(0, s.jitterMs());
            loss[i] = s.lossPct();
        }

        double latencyThreshold = settings.latencyThresholdMs();
        List<TimeChart.Marker> markers = buildMarkers(eventList);

        Platform.runLater(() -> {
            applyRange(from, now);
            latencyChart.threshold(latencyThreshold,
                    I18n.t("chart.thresholdMs", Fmt.dec0(latencyThreshold)));
            latencyChart.setSeries(List.of(
                    new TimeChart.Series(I18n.t("chart.seriesLatency"), Role.ACCENT, ts, latency, true)));
            jitterChart.setSeries(List.of(
                    new TimeChart.Series(I18n.t("chart.seriesJitter"), Role.MUTED, ts, jitter, true)));
            lossChart.setSeries(List.of(
                    new TimeChart.Series(I18n.t("chart.seriesLoss"), Role.WARN, ts, loss, true)));
            applyMarkers(markers);
        });
    }

    private static List<TimeChart.Marker> buildMarkers(List<LineEvent> eventList) {
        List<TimeChart.Marker> markers = new ArrayList<>();
        for (LineEvent e : eventList) {
            Role role = switch (Icons.severity(e.type())) {
                case "bad" -> Role.BAD;
                case "warn" -> Role.WARN;
                case "good" -> Role.GOOD;
                default -> Role.ACCENT;
            };
            markers.add(new TimeChart.Marker(e.ts(), e.type().label(), role));
        }
        return markers;
    }

    private void applyRange(long from, long now) {
        for (TimeChart c : charts) c.setRange(from, now);
        lane.setRange(from, now);
    }

    private void applyMarkers(List<TimeChart.Marker> markers) {
        // olay isaretleri yalnizca kopmalar; her seyi cizmek grafigi kirletiyor
        List<TimeChart.Marker> lineMarks = markers.stream()
                .filter(m -> m.role() == Role.BAD)
                .toList();
        for (TimeChart c : charts) c.setMarkers(lineMarks);
        lane.setMarks(markers);
    }
}
