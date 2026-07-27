package com.emre.linewatch.ui;

import com.emre.linewatch.collector.LineSample;
import com.emre.linewatch.storage.EventRepository;
import com.emre.linewatch.storage.LineEvent;
import com.emre.linewatch.storage.SampleRepository;
import com.emre.linewatch.ui.widget.Role;
import com.emre.linewatch.ui.widget.TimeChart;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Olay gecmisi: solda gun gun grupli zaman cizelgesi, sagda secilen olayin detayi.
 * Kopma olaylarinda kopmadan onceki 10 dakikanin SNR ve CRC seyri de cizilir.
 */
public class EventsView extends HBox {

    private static final String ALL = "Tüm olaylar";
    private static final long PRE_WINDOW_MS = 10 * 60_000L;
    private static final long POST_WINDOW_MS = 2 * 60_000L;

    private final EventRepository events;
    private final SampleRepository samples;

    private final List<LineEvent> all = new ArrayList<>();
    private final VBox timeline = new VBox(1);
    private final TextField search = new TextField();
    private final ComboBox<String> filter = new ComboBox<>();

    private final VBox detail = new VBox(14);
    private final Map<Long, VBox> rows = new LinkedHashMap<>();
    private LineEvent selected;

    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "events-loader");
        t.setDaemon(true);
        return t;
    });

    public EventsView(EventRepository events, SampleRepository samples) {
        this.events = events;
        this.samples = samples;

        getStyleClass().add("page");
        setSpacing(14);

        search.setPromptText("Olaylarda ara…");
        search.textProperty().addListener((obs, old, now) -> render());
        HBox searchBox = Ui.row(8, Icons.search(Icons.SM), search);
        HBox.setHgrow(search, Priority.ALWAYS);

        filter.getItems().add(ALL);
        for (LineEvent.Type t : LineEvent.Type.values()) filter.getItems().add(t.label);
        filter.getSelectionModel().selectFirst();
        filter.setOnAction(e -> render());

        var refresh = Ui.iconButton(Icons.refresh(Icons.SM), "Yenile", "button", "subtle");
        refresh.setOnAction(e -> refresh());

        VBox left = Ui.card(10,
                Ui.row(8, searchBox, filter, refresh),
                scrollable(timeline));
        left.setMinWidth(400);
        left.setPrefWidth(460);

        detail.getStyleClass().add("card");
        showEmptyDetail();

        HBox.setHgrow(detail, Priority.ALWAYS);
        getChildren().addAll(left, detail);
        refresh();
    }

    private static ScrollPane scrollable(Node content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return scroll;
    }

    // ---------- veri ----------

    public void refresh() {
        loader.execute(() -> {
            try {
                List<LineEvent> list = events.findRecent(500);
                Platform.runLater(() -> {
                    all.clear();
                    all.addAll(list);
                    render();
                });
            } catch (Exception e) {
                System.err.println("Olaylar yüklenemedi: " + e.getMessage());
            }
        });
    }

    /** Canli gelen yeni olay (herhangi bir thread). */
    public void addLive(LineEvent e) {
        Platform.runLater(() -> {
            all.add(0, e);
            render();
            VBox row = rows.get(e.id());
            if (row != null) Anim.highlight(row);
        });
    }

    // ---------- zaman cizelgesi ----------

    private void render() {
        String query = search.getText() == null ? "" : search.getText().trim().toLowerCase(Fmt.TR);
        String type = filter.getValue();

        timeline.getChildren().clear();
        rows.clear();

        LocalDate lastDay = null;
        int shown = 0;
        for (LineEvent e : all) {
            if (!ALL.equals(type) && !e.type().label.equals(type)) continue;
            if (!query.isEmpty() && !matches(e, query)) continue;

            LocalDate day = Instant.ofEpochMilli(e.ts()).atZone(ZoneId.systemDefault()).toLocalDate();
            if (!day.equals(lastDay)) {
                Label header = new Label(Fmt.dayHeader(e.ts()));
                header.getStyleClass().add("timeline-day");
                timeline.getChildren().add(header);
                lastDay = day;
            }
            VBox row = buildRow(e);
            rows.put(e.id(), row);
            timeline.getChildren().add(row);
            shown++;
        }

        if (shown == 0) {
            timeline.getChildren().add(all.isEmpty()
                    ? Ui.emptyState("Henüz olay yok", "Hat sorunsuz görünüyor.")
                    : Ui.emptyState("Eşleşen olay yok", "Aramayı ya da filtreyi değiştir."));
        }
    }

    private static boolean matches(LineEvent e, String query) {
        if (e.type().label.toLowerCase(Fmt.TR).contains(query)) return true;
        return e.detail() != null && e.detail().toLowerCase(Fmt.TR).contains(query);
    }

    private VBox buildRow(LineEvent e) {
        Node icon = Icons.forEvent(e.type(), Icons.SM);

        Label time = new Label(Fmt.time(e.ts()));
        time.getStyleClass().add("timeline-time");
        time.setMinWidth(42);

        Label title = new Label(e.type().label);
        title.getStyleClass().add("timeline-title");

        HBox head = Ui.row(9, time, icon, title);
        String durationText = Fmt.duration(e.durationSec());
        if (!durationText.isEmpty()) {
            Label badge = new Label(durationText);
            badge.getStyleClass().add("duration-badge");
            head.getChildren().addAll(Ui.hSpacer(), badge);
        }

        VBox row = new VBox(2, head);
        if (e.detail() != null && !e.detail().isBlank()) {
            Label sub = new Label(e.detail());
            sub.getStyleClass().add("timeline-detail");
            sub.setWrapText(true);
            VBox.setMargin(sub, new javafx.geometry.Insets(0, 0, 0, 51));
            row.getChildren().add(sub);
        }
        row.getStyleClass().add("timeline-item");
        if (selected != null && selected.id() == e.id()) row.getStyleClass().add("selected");
        row.setOnMouseClicked(ev -> select(e));
        return row;
    }

    private void select(LineEvent e) {
        selected = e;
        for (Map.Entry<Long, VBox> entry : rows.entrySet()) {
            entry.getValue().getStyleClass().remove("selected");
        }
        VBox row = rows.get(e.id());
        if (row != null) row.getStyleClass().add("selected");
        showDetail(e);
    }

    // ---------- detay paneli ----------

    private void showEmptyDetail() {
        detail.getChildren().setAll(Ui.emptyState("Bir olay seç",
                "Soldaki listeden bir olaya tıkla; kopma öncesi hattın seyri burada çizilir."));
        detail.setAlignment(Pos.CENTER);
    }

    private void showDetail(LineEvent e) {
        detail.setAlignment(Pos.TOP_LEFT);

        Node icon = Icons.forEvent(e.type(), 18);
        Label title = Ui.h2(e.type().label);
        HBox head = Ui.row(10, icon, title);

        VBox meta = new VBox(4,
                metaRow("Zaman", Fmt.dateTime(e.ts())),
                metaRow("Süre", Fmt.duration(e.durationSec()).isEmpty()
                        ? "kayıtlı değil" : Fmt.duration(e.durationSec())),
                metaRow("Detay", e.detail() == null || e.detail().isBlank() ? "—" : e.detail()));

        Label chartTitle = Ui.cardTitle("OLAY ÖNCESİ 10 DAKİKA");
        TimeChart snrChart = new TimeChart().unit("dB").timeAxis(true);
        snrChart.setPrefHeight(140);
        TimeChart crcChart = new TimeChart().unit("hata").zeroBased(true).timeAxis(true);
        crcChart.setPrefHeight(120);

        detail.getChildren().setAll(head, meta, chartTitle,
                Ui.row(8, Ui.faint("SNR marjı")), snrChart,
                Ui.row(8, Ui.faint("CRC artışı")), crcChart);

        long from = e.ts() - PRE_WINDOW_MS;
        long to = e.ts() + POST_WINDOW_MS;
        loader.execute(() -> {
            try {
                List<LineSample> rows = samples.findRange(from, to, 200);
                int n = rows.size();
                long[] ts = new long[n];
                double[] snrDown = new double[n];
                double[] crcDelta = new double[n];
                long prevCrc = -1;
                for (int i = 0; i < n; i++) {
                    LineSample s = rows.get(i);
                    ts[i] = s.ts();
                    snrDown[i] = s.snrDown() / 10.0;
                    crcDelta[i] = prevCrc >= 0 ? Math.max(0, s.crcDown() - prevCrc) : 0;
                    prevCrc = s.crcDown();
                }
                List<TimeChart.Marker> mark = List.of(
                        new TimeChart.Marker(e.ts(), e.type().label, Role.BAD));
                Platform.runLater(() -> {
                    snrChart.setRange(from, to);
                    crcChart.setRange(from, to);
                    snrChart.setSeries(List.of(
                            new TimeChart.Series("SNR", Role.DOWN, ts, snrDown, true)));
                    crcChart.setSeries(List.of(
                            new TimeChart.Series("CRC artışı", Role.WARN, ts, crcDelta, true)));
                    snrChart.setMarkers(mark);
                    crcChart.setMarkers(mark);
                });
            } catch (Exception ex) {
                System.err.println("Olay detayı yüklenemedi: " + ex.getMessage());
            }
        });
    }

    private static HBox metaRow(String label, String value) {
        Label key = Ui.faint(label);
        key.setMinWidth(60);
        Label val = Ui.muted(value);
        val.setWrapText(true);
        HBox.setHgrow(val, Priority.ALWAYS);
        return Ui.row(10, key, val);
    }
}
