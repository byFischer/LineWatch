package com.emre.linewatch.ui;

import com.emre.linewatch.storage.EventRepository;
import com.emre.linewatch.storage.LineEvent;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.VBox;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/** Kopma/CRC/SNR olay gecmisi tablosu. */
public class EventsView extends VBox {

    public static class Row {
        private final String zaman, olay, detay, sure;
        public Row(LineEvent e) {
            this.zaman = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new Date(e.ts()));
            this.olay = e.type().label;
            this.detay = e.detail() == null ? "" : e.detail();
            this.sure = e.durationSec() > 0 ? e.durationSec() + " sn" : "";
        }
        public String getZaman() { return zaman; }
        public String getOlay() { return olay; }
        public String getDetay() { return detay; }
        public String getSure() { return sure; }
    }

    private final EventRepository events;
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final javafx.collections.transformation.FilteredList<Row> filtered =
            new javafx.collections.transformation.FilteredList<>(rows);
    private final javafx.scene.control.ComboBox<String> filter = new javafx.scene.control.ComboBox<>();

    public EventsView(EventRepository events) {
        this.events = events;
        setPadding(new Insets(12));
        setSpacing(10);

        filter.getItems().add("Tum olaylar");
        for (LineEvent.Type t : LineEvent.Type.values()) filter.getItems().add(t.label);
        filter.getSelectionModel().selectFirst();
        filter.setOnAction(e -> {
            String sel = filter.getValue();
            filtered.setPredicate("Tum olaylar".equals(sel) ? null : r -> r.getOlay().equals(sel));
        });

        javafx.scene.control.Button refreshBtn = new javafx.scene.control.Button("Yenile");
        refreshBtn.getStyleClass().add("secondary");
        refreshBtn.setOnAction(e -> refresh());

        javafx.scene.control.Label title = new javafx.scene.control.Label("FILTRE");
        title.getStyleClass().add("section-title");
        javafx.scene.layout.HBox toolbar = new javafx.scene.layout.HBox(10, title, filter, refreshBtn);
        toolbar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        TableView<Row> table = new TableView<>(filtered);
        table.setPlaceholder(new javafx.scene.control.Label("Henuz olay yok — hat sorunsuz gorunuyor"));
        table.getColumns().addAll(List.of(
                col("Zaman", "zaman", 150),
                col("Olay", "olay", 160),
                col("Detay", "detay", 420),
                col("Sure", "sure", 80)));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        getChildren().addAll(toolbar, table);
        VBox.setVgrow(table, javafx.scene.layout.Priority.ALWAYS);
        refresh();
    }

    /** Yeni olay geldiginde canli ekleme (herhangi bir thread). */
    public void addLive(LineEvent e) {
        Platform.runLater(() -> rows.add(0, new Row(e)));
    }

    public void refresh() {
        Thread t = new Thread(() -> {
            try {
                List<LineEvent> list = events.findRecent(500);
                Platform.runLater(() -> {
                    rows.clear();
                    list.forEach(e -> rows.add(new Row(e)));
                });
            } catch (Exception ignored) {
            }
        }, "events-refresh");
        t.setDaemon(true);
        t.start();
    }

    private static TableColumn<Row, String> col(String title, String prop, int width) {
        TableColumn<Row, String> c = new TableColumn<>(title);
        c.setCellValueFactory(new PropertyValueFactory<>(prop));
        c.setPrefWidth(width);
        return c;
    }
}
