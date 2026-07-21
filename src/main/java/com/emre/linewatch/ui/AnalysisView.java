package com.emre.linewatch.ui;

import com.emre.linewatch.storage.EventRepository;
import com.emre.linewatch.storage.LineEvent;
import com.emre.linewatch.storage.SampleRepository;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 7x24 isi haritasi: hangi gun/saatte CRC patliyor ya da hat kopuyor?
 * Tek sekans mavi rampa (koyu -> acik), log olcek.
 */
public class AnalysisView extends VBox {

    private static final String[] DAYS = {"Pzt", "Sal", "Car", "Per", "Cum", "Cmt", "Paz"};

    private final SampleRepository samples;
    private final EventRepository events;
    private final ComboBox<String> metric = new ComboBox<>();
    private final ComboBox<String> period = new ComboBox<>();
    private final Region[][] cells = new Region[7][24];
    private final Label info = new Label();
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "analysis-loader");
        t.setDaemon(true);
        return t;
    });

    public AnalysisView(SampleRepository samples, EventRepository events) {
        this.samples = samples;
        this.events = events;
        setPadding(new Insets(16));
        setSpacing(14);

        metric.getItems().addAll("CRC hata artisi", "Kopma sayisi");
        metric.getSelectionModel().selectFirst();
        metric.setOnAction(e -> reload());
        period.getItems().addAll("Son 7 gun", "Son 30 gun");
        period.getSelectionModel().selectFirst();
        period.setOnAction(e -> reload());
        Button refresh = new Button("Yenile");
        refresh.getStyleClass().add("secondary");
        refresh.setOnAction(e -> reload());

        Label t1 = new Label("METRIK");
        t1.getStyleClass().add("section-title");
        Label t2 = new Label("DONEM");
        t2.getStyleClass().add("section-title");
        HBox toolbar = new HBox(10, t1, metric, t2, period, refresh);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        GridPane grid = new GridPane();
        grid.setHgap(3);
        grid.setVgap(3);
        // saat basliklari
        for (int h = 0; h < 24; h++) {
            Label l = new Label(String.format("%02d", h));
            l.getStyleClass().add("card-sub");
            GridPane.setHalignment(l, javafx.geometry.HPos.CENTER);
            grid.add(l, h + 1, 0);
        }
        for (int d = 0; d < 7; d++) {
            Label l = new Label(DAYS[d]);
            l.getStyleClass().add("card-sub");
            grid.add(l, 0, d + 1);
            for (int h = 0; h < 24; h++) {
                Region cell = new Region();
                cell.setMinSize(20, 26);
                cell.setMaxWidth(Double.MAX_VALUE);
                GridPane.setHgrow(cell, Priority.ALWAYS);
                cells[d][h] = cell;
                grid.add(cell, h + 1, d + 1);
            }
        }

        Label legendLow = new Label("az");
        legendLow.getStyleClass().add("card-sub");
        Label legendHigh = new Label("cok");
        legendHigh.getStyleClass().add("card-sub");
        HBox legend = new HBox(6, legendLow, ramp(), legendHigh);
        legend.setAlignment(Pos.CENTER_LEFT);
        info.getStyleClass().add("card-sub");

        VBox card = new VBox(12, grid, new HBox(20, legend, info));
        card.getStyleClass().add("card");
        getChildren().addAll(toolbar, card);
        reload();
    }

    private HBox ramp() {
        HBox box = new HBox(1);
        for (int i = 0; i < 8; i++) {
            Region r = new Region();
            r.setMinSize(18, 12);
            r.setStyle("-fx-background-color:" + color(i / 7.0) + "; -fx-background-radius:2;");
            box.getChildren().add(r);
        }
        return box;
    }

    private void reload() {
        boolean crcMode = metric.getSelectionModel().getSelectedIndex() == 0;
        int days = period.getSelectionModel().getSelectedIndex() == 0 ? 7 : 30;
        long from = System.currentTimeMillis() - days * 24L * 3600_000;
        loader.execute(() -> {
            try {
                double[][] matrix = new double[7][24];
                if (crcMode) {
                    List<long[]> pts = samples.findCrcPointsSince(from);
                    long prev = -1;
                    for (long[] p : pts) {
                        if (prev >= 0 && p[1] >= prev) {
                            LocalDateTime dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(p[0]), ZoneId.systemDefault());
                            matrix[dt.getDayOfWeek().getValue() - 1][dt.getHour()] += p[1] - prev;
                        }
                        prev = p[1];
                    }
                } else {
                    for (long ts : events.timesSince(from, LineEvent.Type.LINK_DOWN)) {
                        LocalDateTime dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault());
                        matrix[dt.getDayOfWeek().getValue() - 1][dt.getHour()]++;
                    }
                }
                double max = 0, total = 0;
                for (double[] row : matrix) for (double v : row) { max = Math.max(max, v); total += v; }
                final double fMax = max, fTotal = total;
                Platform.runLater(() -> render(matrix, fMax, fTotal, crcMode, days));
            } catch (Exception e) {
                Platform.runLater(() -> info.setText("Veri yuklenemedi: " + e.getMessage()));
            }
        });
    }

    private void render(double[][] matrix, double max, double total, boolean crcMode, int days) {
        for (int d = 0; d < 7; d++) {
            for (int h = 0; h < 24; h++) {
                double v = matrix[d][h];
                // log olcek: tek buyuk patlama diger her seyi soldurmasin
                double norm = max > 0 && v > 0 ? Math.log1p(v) / Math.log1p(max) : 0;
                cells[d][h].setStyle("-fx-background-color:" + color(norm) + "; -fx-background-radius:3;");
                Tooltip.install(cells[d][h], new Tooltip(DAYS[d] + " " + String.format("%02d:00", h)
                        + " — " + (crcMode ? String.format("%,.0f CRC", v) : String.format("%.0f kopma", v))));
            }
        }
        info.setText(String.format("son %d gun toplam: %s", days,
                crcMode ? String.format("%,.0f CRC hatasi", total) : String.format("%.0f kopma", total)));
    }

    /** 0..1 -> koyu zemin (#101a2c) ile acik mavi (#8ec6f5) arasi. */
    private static String color(double t) {
        int r = (int) (0x10 + t * (0x8e - 0x10));
        int g = (int) (0x1a + t * (0xc6 - 0x1a));
        int b = (int) (0x2c + t * (0xf5 - 0x2c));
        return String.format("#%02x%02x%02x", r, g, b);
    }
}
