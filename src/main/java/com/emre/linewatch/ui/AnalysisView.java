package com.emre.linewatch.ui;

import com.emre.linewatch.storage.EventRepository;
import com.emre.linewatch.storage.LineEvent;
import com.emre.linewatch.storage.SampleRepository;
import com.emre.linewatch.ui.widget.HeatMap;
import com.emre.linewatch.ui.widget.Segmented;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 7x24 isi haritasi: hafta icinde hangi gun ve saatte CRC patliyor ya da hat kopuyor?
 * Kenarlardaki toplam cubuklari gunluk ve saatlik dagilimi ozetler.
 */
public class AnalysisView extends VBox {

    private static final String[] DAYS = {"Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz"};
    private static final int RAMP_STEPS = 9;

    private final SampleRepository samples;
    private final EventRepository events;

    private final Segmented metric = new Segmented("CRC hata artışı", "Kopma sayısı");
    private final Segmented period = new Segmented("Son 7 gün", "Son 30 gün");
    private final HeatMap heat = new HeatMap();
    private final Label total = Ui.faint("");
    private final Label hover = Ui.muted("Hücrenin üzerine gel");
    private final VBox worst = new VBox(6);
    private final List<Region> rampCells = new ArrayList<>();

    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "analysis-loader");
        t.setDaemon(true);
        return t;
    });

    public AnalysisView(SampleRepository samples, EventRepository events) {
        this.samples = samples;
        this.events = events;

        getStyleClass().add("page");
        setSpacing(14);

        metric.setOnSelect(i -> reload());
        period.setOnSelect(i -> reload());
        HBox toolbar = Ui.row(14, Ui.cardTitle("METRİK"), metric,
                Ui.cardTitle("DÖNEM"), period, Ui.hSpacer(), hover);

        heat.setOnHover(text -> hover.setText(text == null ? "Hücrenin üzerine gel" : text));
        VBox heatCard = Ui.card(12, heat, Ui.row(16, buildLegend(), Ui.hSpacer(), total));
        VBox.setVgrow(heat, Priority.ALWAYS);
        VBox.setVgrow(heatCard, Priority.ALWAYS);

        VBox worstCard = Ui.card(10, Ui.cardTitle("EN YOĞUN SAAT DİLİMLERİ"), worst);

        getChildren().addAll(toolbar, heatCard, worstCard);

        Theme.paletteProperty().addListener((obs, old, now) -> paintRamp());
        reload();
    }

    private HBox buildLegend() {
        HBox ramp = new HBox(2);
        for (int i = 0; i < RAMP_STEPS; i++) {
            Region cell = new Region();
            cell.setMinSize(16, 11);
            cell.setPrefSize(16, 11);
            rampCells.add(cell);
            ramp.getChildren().add(cell);
        }
        paintRamp();
        return Ui.row(8, Ui.faint("az"), ramp, Ui.faint("çok"));
    }

    private void paintRamp() {
        for (int i = 0; i < rampCells.size(); i++) {
            var color = HeatMap.rampSample(i / (double) (RAMP_STEPS - 1));
            rampCells.get(i).setStyle("-fx-background-color: " + hex(color) + "; -fx-background-radius: 2;");
        }
    }

    private static String hex(javafx.scene.paint.Color c) {
        return String.format("#%02x%02x%02x",
                (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
    }

    private void reload() {
        boolean crcMode = metric.selected() == 0;
        int days = period.selected() == 0 ? 7 : 30;
        long from = System.currentTimeMillis() - days * 24L * 3600_000;

        loader.execute(() -> {
            try {
                double[][] matrix = new double[7][24];
                if (crcMode) {
                    long prev = -1;
                    for (long[] point : samples.findCrcPointsSince(from)) {
                        if (prev >= 0 && point[1] >= prev) {
                            LocalDateTime dt = at(point[0]);
                            matrix[dt.getDayOfWeek().getValue() - 1][dt.getHour()] += point[1] - prev;
                        }
                        prev = point[1];
                    }
                } else {
                    for (long ts : events.timesSince(from, LineEvent.Type.LINK_DOWN)) {
                        LocalDateTime dt = at(ts);
                        matrix[dt.getDayOfWeek().getValue() - 1][dt.getHour()]++;
                    }
                }

                double max = 0;
                double sum = 0;
                for (double[] row : matrix) {
                    for (double v : row) {
                        max = Math.max(max, v);
                        sum += v;
                    }
                }
                List<Cell> top = topCells(matrix, 3);

                final double fMax = max;
                final double fSum = sum;
                Platform.runLater(() -> {
                    String unit = crcMode ? "CRC" : "kopma";
                    heat.setData(matrix, fMax, unit);
                    total.setText("son " + days + " gün toplamı: "
                            + Fmt.count(Math.round(fSum)) + " " + unit);
                    renderWorst(top, unit, fSum);
                });
            } catch (Exception e) {
                Platform.runLater(() -> total.setText("Veri yüklenemedi: " + e.getMessage()));
            }
        });
    }

    private void renderWorst(List<Cell> top, String unit, double sum) {
        worst.getChildren().clear();
        if (top.isEmpty() || sum <= 0) {
            worst.getChildren().add(Ui.faint("Bu dönemde kayda değer bir yoğunluk yok."));
            return;
        }
        for (Cell cell : top) {
            Label when = Ui.body(DAYS[cell.day()] + " " + String.format("%02d:00", cell.hour()));
            Label value = Ui.muted(Fmt.count(Math.round(cell.value())) + " " + unit);
            Label share = Ui.faint("toplamın " + Fmt.pct0(100.0 * cell.value() / sum) + "'i");
            worst.getChildren().add(Ui.row(10, when, Ui.hSpacer(), value, share));
        }
    }

    private static LocalDateTime at(long ts) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault());
    }

    private record Cell(int day, int hour, double value) {
    }

    private static List<Cell> topCells(double[][] matrix, int limit) {
        List<Cell> cells = new ArrayList<>();
        for (int d = 0; d < 7; d++) {
            for (int h = 0; h < 24; h++) {
                if (matrix[d][h] > 0) cells.add(new Cell(d, h, matrix[d][h]));
            }
        }
        cells.sort(Comparator.comparingDouble(Cell::value).reversed());
        return cells.subList(0, Math.min(limit, cells.size()));
    }
}
