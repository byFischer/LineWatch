package com.emre.linewatch;

import com.emre.linewatch.alert.AlertEngine;
import com.emre.linewatch.alert.Notifier;
import com.emre.linewatch.alert.TelegramNotifier;
import com.emre.linewatch.alert.ToastNotifier;
import com.emre.linewatch.collector.TpLinkCollector;
import com.emre.linewatch.config.Settings;
import com.emre.linewatch.storage.Database;
import com.emre.linewatch.storage.EventRepository;
import com.emre.linewatch.storage.SampleRepository;
import com.emre.linewatch.ui.ChartsView;
import com.emre.linewatch.ui.DashboardView;
import com.emre.linewatch.ui.EventsView;
import com.emre.linewatch.ui.SettingsView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;

import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * LineWatch: tray'de yasayan DSL hat izleyici.
 * Pencere kapatilinca uygulama kapanmaz, tray'e kucultur.
 */
public class App extends Application {

    private Settings settings;
    private Database db;
    private MonitorService monitor;
    private TrayIcon trayIcon;
    private Stage stage;
    private final javafx.scene.control.Label statusPill = new javafx.scene.control.Label();

    private void setPill(boolean up) {
        statusPill.setText(up ? "HAT AKTIF" : "SORUN VAR");
        statusPill.getStyleClass().removeAll("status-pill-up", "status-pill-down", "status-pill-unknown");
        statusPill.getStyleClass().add(up ? "status-pill-up" : "status-pill-down");
    }


    @Override
    public void start(Stage primaryStage) throws Exception {
        this.stage = primaryStage;
        Platform.setImplicitExit(false);

        settings = Settings.load();
        db = new Database();
        SampleRepository samples = new SampleRepository(db);
        EventRepository events = new EventRepository(db);

        TelegramNotifier telegram = new TelegramNotifier(settings);
        List<Notifier> notifiers = new ArrayList<>();
        notifiers.add((title, msg, urgent) -> {
            if (settings.toastEnabled()) new ToastNotifier(() -> trayIcon).notify(title, msg, urgent);
        });
        notifiers.add(telegram);

        AlertEngine alerts = new AlertEngine(settings, events, samples, notifiers);
        monitor = new MonitorService(settings, samples, alerts, telegram);
        new com.emre.linewatch.alert.TelegramBot(settings, telegram, monitor, events).start();

        // UI
        DashboardView dashboard = new DashboardView(samples, events);
        ChartsView charts = new ChartsView(samples);
        EventsView eventsView = new EventsView(events);
        SettingsView settingsView = new SettingsView(settings, this::applySettings);

        alerts.setEventListener(eventsView::addLive);
        monitor.addSampleListener(s -> {
            dashboard.update(s);
            charts.append(s);
            updateTrayTooltip(s.isUp()
                    ? String.format("LineWatch — Up, SNR %.1f dB, %d CRC", s.snrDown() / 10.0, s.crcDown())
                    : "LineWatch — HAT KOPUK");
        });
        monitor.addStatusListener(err -> {
            if (err != null) dashboard.showError(err);
        });

        com.emre.linewatch.ui.AnalysisView analysisView =
                new com.emre.linewatch.ui.AnalysisView(samples, events);

        TabPane tabs = new TabPane(
                tab("Pano", dashboard),
                tab("Grafikler", charts),
                tab("Analiz", analysisView),
                tab("Olaylar", eventsView),
                tab("Ayarlar", settingsView));

        // ust baslik cubugu
        javafx.scene.control.Label title = new javafx.scene.control.Label("LINE//WATCH");
        title.getStyleClass().add("app-title");
        javafx.scene.control.Label subtitle = new javafx.scene.control.Label("Archer VR400 — DSL hat izleyici");
        subtitle.getStyleClass().add("app-subtitle");
        javafx.scene.layout.Region hSpacer = new javafx.scene.layout.Region();
        javafx.scene.layout.HBox.setHgrow(hSpacer, javafx.scene.layout.Priority.ALWAYS);
        statusPill.getStyleClass().addAll("status-pill", "status-pill-unknown");
        statusPill.setText("BEKLENIYOR");

        javafx.scene.control.Button rebootBtn = new javafx.scene.control.Button("⟳ Modemi Yeniden Baslat");
        rebootBtn.getStyleClass().add("secondary");
        rebootBtn.setOnAction(e -> {
            javafx.scene.control.Alert confirm = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.CONFIRMATION,
                    "Modem yeniden baslatilacak; internet 2-3 dakika kesilir. Emin misin?",
                    javafx.scene.control.ButtonType.YES, javafx.scene.control.ButtonType.NO);
            confirm.setHeaderText("Modemi yeniden baslat");
            confirm.initOwner(stage);
            confirm.showAndWait().ifPresent(bt -> {
                if (bt == javafx.scene.control.ButtonType.YES) {
                    rebootBtn.setDisable(true);
                    monitor.rebootModem(err -> Platform.runLater(() -> {
                        rebootBtn.setDisable(false);
                        if (err == null) {
                            try {
                                events.insert(com.emre.linewatch.storage.LineEvent.Type.REBOOT,
                                        "Kullanici istegiyle uygulamadan yeniden baslatildi", 0);
                            } catch (Exception ignored) {
                            }
                            if (trayIcon != null) trayIcon.displayMessage("LineWatch",
                                    "Reboot komutu gonderildi, modem yeniden basliyor...",
                                    TrayIcon.MessageType.INFO);
                        } else {
                            new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR,
                                    "Reboot gonderilemedi: " + err).show();
                        }
                    }));
                }
            });
        });

        javafx.scene.layout.HBox header = new javafx.scene.layout.HBox(12,
                new javafx.scene.layout.VBox(2, title, subtitle), hSpacer, rebootBtn, statusPill);
        header.getStyleClass().add("app-header");
        header.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        javafx.scene.layout.BorderPane rootPane = new javafx.scene.layout.BorderPane(tabs);
        rootPane.setTop(header);

        monitor.addSampleListener(s -> Platform.runLater(() -> setPill(s.isUp())));
        monitor.addStatusListener(err -> {
            if (err != null) Platform.runLater(() -> setPill(false));
        });

        Scene scene = new Scene(rootPane, 980, 720);
        scene.getStylesheets().add(getClass().getResource("styles.css").toExternalForm());
        stage.setTitle("LineWatch — DSL Hat Izleyici");
        stage.setScene(scene);
        for (int sz : new int[]{16, 24, 32, 48, 64, 128, 256}) {
            var url = getClass().getResource("icon-" + sz + ".png");
            if (url != null) stage.getIcons().add(new javafx.scene.image.Image(url.toExternalForm()));
        }
        stage.setOnCloseRequest(e -> {
            e.consume();
            stage.hide();
        });

        setupTray();

        if (settings.modemPassword().isBlank()) {
            tabs.getSelectionModel().select(3);
            stage.show();
        } else {
            monitor.setCollector(new TpLinkCollector(settings.modemHost(), settings.modemPassword()));
            stage.show();
        }
        monitor.start();
    }

    private void applySettings() {
        monitor.restart(new TpLinkCollector(settings.modemHost(), settings.modemPassword()));
    }

    private static Tab tab(String title, javafx.scene.Node content) {
        Tab t = new Tab(title, content);
        t.setClosable(false);
        return t;
    }

    private void setupTray() {
        if (!SystemTray.isSupported()) return;
        java.awt.EventQueue.invokeLater(() -> {
            try {
                PopupMenu menu = new PopupMenu();
                MenuItem open = new MenuItem("Ac");
                open.addActionListener(e -> Platform.runLater(() -> {
                    stage.show();
                    stage.toFront();
                }));
                MenuItem exit = new MenuItem("Cikis");
                exit.addActionListener(e -> shutdown());
                menu.add(open);
                menu.addSeparator();
                menu.add(exit);

                trayIcon = new TrayIcon(trayImage(), "LineWatch", menu);
                trayIcon.setImageAutoSize(true);
                trayIcon.addActionListener(e -> Platform.runLater(() -> {
                    stage.show();
                    stage.toFront();
                }));
                SystemTray.getSystemTray().add(trayIcon);
            } catch (Exception e) {
                System.err.println("Tray kurulamadi: " + e.getMessage());
            }
        });
    }

    private void updateTrayTooltip(String text) {
        TrayIcon icon = trayIcon;
        if (icon != null) java.awt.EventQueue.invokeLater(() -> icon.setToolTip(text));
    }

    private static BufferedImage trayImage() {
        try (var in = App.class.getResourceAsStream("icon-32.png")) {
            if (in != null) return javax.imageio.ImageIO.read(in);
        } catch (Exception ignored) {
        }
        // kaynak bulunamazsa yedek: mavi daire icinde beyaz sinyal cizgisi
        int sz = 16;
        BufferedImage img = new BufferedImage(sz, sz, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new java.awt.Color(0x3d, 0x85, 0xcc));
        g.fillOval(0, 0, sz - 1, sz - 1);
        g.setColor(java.awt.Color.WHITE);
        g.setStroke(new java.awt.BasicStroke(2f));
        g.drawPolyline(new int[]{3, 6, 9, 12}, new int[]{11, 6, 9, 4}, 4);
        g.dispose();
        return img;
    }

    private void shutdown() {
        monitor.shutdown();
        if (trayIcon != null) SystemTray.getSystemTray().remove(trayIcon);
        db.close();
        Platform.exit();
        System.exit(0);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
