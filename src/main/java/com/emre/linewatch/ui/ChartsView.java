package com.emre.linewatch.ui;

import com.emre.linewatch.collector.LineSample;
import com.emre.linewatch.storage.SampleRepository;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Zaman serisi grafikleri: SNR, CRC delta, sync hizi.
 * Renkler: down = mavi #3466cd, up = turuncu #e0862e (CVD-dogrulanmis cift).
 */
public class ChartsView extends VBox {

    private static final int MAX_POINTS = 240;

    private final SampleRepository samples;
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "chart-loader");
        t.setDaemon(true);
        return t;
    });

    private final ComboBox<String> range = new ComboBox<>();
    private final XYChart.Series<String, Number> snrDown = named("SNR Down (dB)");
    private final XYChart.Series<String, Number> snrUp = named("SNR Up (dB)");
    private final XYChart.Series<String, Number> crcDelta = named("CRC artisi");
    private final XYChart.Series<String, Number> syncDown = named("Sync Down (Mbps)");
    private final XYChart.Series<String, Number> syncUp = named("Sync Up (Mbps)");

    private long lastCrc = -1;
    private final SimpleDateFormat hm = new SimpleDateFormat("HH:mm:ss");
    private final SimpleDateFormat dhm = new SimpleDateFormat("dd.MM HH:mm");

    public ChartsView(SampleRepository samples) {
        this.samples = samples;
        setSpacing(10);
        setPadding(new Insets(12));

        range.getItems().addAll("Canli (son 1 saat)", "Son 24 saat", "Son 7 gun");
        range.getSelectionModel().selectFirst();
        range.setOnAction(e -> reload());

        LineChart<String, Number> snrChart = chart("SNR Margin (dB)", "dB", List.of(snrDown, snrUp));
        LineChart<String, Number> syncChart = chart("Senkron Hizi (Mbps)", "Mbps", List.of(syncDown, syncUp));
        LineChart<String, Number> crcChart = chart("CRC Hata Artisi (ornekleme basina)", "hata", List.of(crcDelta));

        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(14);
        for (int i = 0; i < 2; i++) {
            ColumnConstraints c = new ColumnConstraints();
            c.setPercentWidth(50);
            grid.getColumnConstraints().add(c);
        }
        grid.add(wrap(snrChart), 0, 0);
        grid.add(wrap(syncChart), 1, 0);
        StackPane crcWrap = wrap(crcChart);
        GridPane.setColumnSpan(crcWrap, 2);
        grid.add(crcWrap, 0, 1);

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        Label rangeLabel = new Label("ARALIK");
        rangeLabel.getStyleClass().add("section-title");
        HBox toolbar = new HBox(10, rangeLabel, range);
        toolbar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        getChildren().addAll(toolbar, scroll);
        reload();
    }

    /** Canli modda yeni ornegi grafige ekler (herhangi bir thread). */
    public void append(LineSample s) {
        if (range.getSelectionModel().getSelectedIndex() != 0) return;
        long prevCrc = lastCrc;
        lastCrc = s.crcDown();
        Platform.runLater(() -> {
            String label = hm.format(new Date(s.ts()));
            add(snrDown, label, s.snrDown() / 10.0);
            add(snrUp, label, s.snrUp() / 10.0);
            add(crcDelta, label, prevCrc >= 0 ? Math.max(0, s.crcDown() - prevCrc) : 0);
            add(syncDown, label, s.syncDown() / 1000.0);
            add(syncUp, label, s.syncUp() / 1000.0);
        });
    }

    private void reload() {
        int idx = range.getSelectionModel().getSelectedIndex();
        long now = System.currentTimeMillis();
        long from = switch (idx) {
            case 1 -> now - 24L * 3600_000;
            case 2 -> now - 7L * 24 * 3600_000;
            default -> now - 3600_000;
        };
        SimpleDateFormat fmt = idx == 0 ? hm : dhm;
        loader.execute(() -> {
            try {
                List<LineSample> rows = samples.findRange(from, now, MAX_POINTS);
                Platform.runLater(() -> {
                    clearAll();
                    long prevCrc = -1;
                    for (LineSample s : rows) {
                        String label = fmt.format(new Date(s.ts()));
                        add(snrDown, label, s.snrDown() / 10.0);
                        add(snrUp, label, s.snrUp() / 10.0);
                        add(crcDelta, label, prevCrc >= 0 ? Math.max(0, s.crcDown() - prevCrc) : 0);
                        add(syncDown, label, s.syncDown() / 1000.0);
                        add(syncUp, label, s.syncUp() / 1000.0);
                        prevCrc = s.crcDown();
                    }
                    lastCrc = prevCrc;
                });
            } catch (Exception e) {
                System.err.println("Grafik verisi yuklenemedi: " + e.getMessage());
            }
        });
    }

    private void clearAll() {
        for (XYChart.Series<String, Number> s : List.of(snrDown, snrUp, crcDelta, syncDown, syncUp)) {
            s.getData().clear();
        }
    }

    private void add(XYChart.Series<String, Number> series, String label, Number value) {
        series.getData().add(new XYChart.Data<>(label, value));
        if (series.getData().size() > MAX_POINTS) series.getData().remove(0);
    }

    private static XYChart.Series<String, Number> named(String name) {
        XYChart.Series<String, Number> s = new XYChart.Series<>();
        s.setName(name);
        return s;
    }

    @SafeVarargs
    private LineChart<String, Number> chart(String title, String yLabel,
                                            List<XYChart.Series<String, Number>>... seriesLists) {
        CategoryAxis x = new CategoryAxis();
        x.setTickLabelRotation(45);
        NumberAxis y = new NumberAxis();
        y.setLabel(yLabel);
        y.setForceZeroInRange(false);
        LineChart<String, Number> c = new LineChart<>(x, y);
        c.setTitle(title);
        c.setCreateSymbols(false);
        c.setAnimated(false);
        c.setMinHeight(260);
        c.setPrefHeight(300);
        for (List<XYChart.Series<String, Number>> list : seriesLists) c.getData().addAll(list);
        return c;
    }

    /** Grafigi kart cercevesine alir. */
    private StackPane wrap(LineChart<String, Number> chart) {
        StackPane p = new StackPane(chart);
        p.getStyleClass().add("card");
        return p;
    }
}
