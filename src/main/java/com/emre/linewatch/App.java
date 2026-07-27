package com.emre.linewatch;

import com.emre.linewatch.alert.AlertEngine;
import com.emre.linewatch.alert.Notifier;
import com.emre.linewatch.alert.TelegramBot;
import com.emre.linewatch.alert.TelegramNotifier;
import com.emre.linewatch.alert.ToastNotifier;
import com.emre.linewatch.collector.LineSample;
import com.emre.linewatch.collector.TpLinkCollector;
import com.emre.linewatch.config.Settings;
import com.emre.linewatch.storage.Database;
import com.emre.linewatch.storage.EventRepository;
import com.emre.linewatch.storage.LineEvent;
import com.emre.linewatch.storage.SampleRepository;
import com.emre.linewatch.ui.AnalysisView;
import com.emre.linewatch.ui.Anim;
import com.emre.linewatch.ui.ChartsView;
import com.emre.linewatch.ui.DashboardView;
import com.emre.linewatch.ui.EventsView;
import com.emre.linewatch.ui.Fmt;
import com.emre.linewatch.ui.Icons;
import com.emre.linewatch.ui.SettingsView;
import com.emre.linewatch.ui.Theme;
import com.emre.linewatch.ui.Ui;
import com.emre.linewatch.ui.WindowChrome;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LineWatch: tray'de yasayan DSL hat izleyici.
 * Pencere kapatilinca uygulama kapanmaz, tray'e kuculur.
 */
public class App extends Application {

    private Settings settings;
    private Database db;
    private MonitorService monitor;
    private TrayIcon trayIcon;
    private Stage stage;
    private WindowChrome chrome;

    private final StackPane content = new StackPane();
    private final List<HBox> navItems = new ArrayList<>();
    private final List<Node> views = new ArrayList<>();

    private static final String[] PAGE_NAMES = {"Pano", "Grafikler", "Analiz", "Olaylar", "Ayarlar"};

    private final Circle statusDot = Ui.dot(5, "unknown");
    private final Label statusLabel = new Label("BEKLENİYOR");
    private final Label pageTitle = new Label(PAGE_NAMES[0]);
    private Button maximizeButton;
    private Node titleBarDragHandle;
    private volatile String trayState = "unknown";

    @Override
    public void start(Stage primaryStage) throws Exception {
        this.stage = primaryStage;
        Platform.setImplicitExit(false);

        settings = Settings.load();
        applyThemePreference();

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
        new TelegramBot(settings, telegram, monitor, events).start();

        DashboardView dashboard = new DashboardView(samples, events);
        ChartsView charts = new ChartsView(samples, events, settings);
        AnalysisView analysis = new AnalysisView(samples, events);
        EventsView eventsView = new EventsView(events, samples);
        SettingsView settingsView = new SettingsView(settings, this::applySettings);
        views.addAll(List.of(dashboard, charts, analysis, eventsView, settingsView));

        alerts.setEventListener(eventsView::addLive);
        monitor.addSampleListener(s -> {
            dashboard.update(s);
            charts.onSample(s);
            updateStatus(s);
        });
        monitor.addStatusListener(error -> {
            if (error == null) return;
            dashboard.showError(error);
            Platform.runLater(() -> setStatus("unknown", "BAĞLANTI YOK"));
            setTray("unknown", "LineWatch — modeme ulaşılamıyor");
        });

        BorderPane appRoot = buildShell(events);
        Scene scene = new Scene(appRoot, 1180, 780);
        Theme.install(scene);

        stage.initStyle(StageStyle.UNDECORATED);
        stage.setScene(scene);
        stage.setTitle("LineWatch — DSL Hat İzleyici");
        stage.setMinWidth(1000);
        stage.setMinHeight(660);
        for (int size : new int[]{16, 24, 32, 48, 64, 128, 256}) {
            var url = getClass().getResource("icon-" + size + ".png");
            if (url != null) stage.getIcons().add(new javafx.scene.image.Image(url.toExternalForm()));
        }
        stage.setOnCloseRequest(e -> {
            e.consume();
            stage.hide();
        });
        // yerel tema takibi yoksa pencereye donuldugunde sistem temasi yeniden okunur
        stage.focusedProperty().addListener((obs, was, focused) -> {
            if (focused) Theme.refreshSystemTheme();
        });

        chrome = new WindowChrome(stage, appRoot);
        chrome.setOnMaximizedChanged(() -> maximizeButton.setGraphic(
                chrome.isMaximized() ? Icons.restore(Icons.WIN) : Icons.maximize(Icons.WIN)));
        chrome.makeDraggable(titleBarDragHandle);

        setupTray();

        boolean needsSetup = settings.modemPassword().isBlank();
        select(needsSetup ? 4 : 0);
        if (!needsSetup) {
            monitor.setCollector(new TpLinkCollector(settings.modemHost(), settings.modemPassword()));
        }
        stage.show();
        monitor.start();
    }

    private void applyThemePreference() {
        try {
            Theme.setMode(Theme.Mode.valueOf(settings.theme()));
        } catch (IllegalArgumentException ignored) {
            Theme.setMode(Theme.Mode.AUTO);
        }
    }

    // ---------- kabuk ----------

    private BorderPane buildShell(EventRepository events) {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-root");
        root.setTop(buildTitleBar());
        root.setLeft(buildNavRail(events));
        content.getStyleClass().add("content-area");
        root.setCenter(content);
        return root;
    }

    private HBox buildTitleBar() {
        // marka sol menude duruyor; baslik cubugunda acik olan ekranin adi daha bilgilendirici
        pageTitle.getStyleClass().add("title-bar-text");

        statusLabel.getStyleClass().add("status-chip");
        HBox status = Ui.row(7, statusDot, statusLabel);

        Button minimize = winButton(Icons.minimize(Icons.WIN), "Küçült");
        minimize.setOnAction(e -> stage.setIconified(true));
        maximizeButton = winButton(Icons.maximize(Icons.WIN), "Büyült");
        maximizeButton.setOnAction(e -> chrome.toggleMaximize());
        Button close = winButton(Icons.close(Icons.WIN), "Tray'e küçült");
        close.getStyleClass().add("close");
        close.setOnAction(e -> stage.hide());

        // pencere dugmeleri disinda kalan bolum surukleme alani
        HBox dragArea = Ui.row(12, pageTitle, Ui.hSpacer(), status);
        HBox.setHgrow(dragArea, Priority.ALWAYS);
        titleBarDragHandle = dragArea;

        HBox bar = new HBox(6, dragArea, minimize, maximizeButton, close);
        bar.getStyleClass().add("title-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private static Button winButton(Node glyph, String tooltip) {
        Button button = new Button();
        button.setGraphic(glyph);
        // setAll: varsayilan "button" sinifini da dusuruyoruz, yoksa birincil
        // buton stili (dolu accent zemin) pencere dugmelerini de boyuyor
        button.getStyleClass().setAll("win-button");
        Ui.install(button, tooltip);
        return button;
    }

    private VBox buildNavRail(EventRepository events) {
        Label brand = new Label("LineWatch");
        brand.getStyleClass().add("nav-brand");
        Label sub = new Label("Archer VR400");
        sub.getStyleClass().add("nav-brand-sub");
        VBox brandBox = new VBox(1, brand, sub);
        brandBox.setPadding(new Insets(10, 12, 16, 12));

        VBox items = new VBox(2,
                navItem("Pano", Icons.layout(Icons.NAV), 0),
                navItem("Grafikler", Icons.activity(Icons.NAV), 1),
                navItem("Analiz", Icons.grid(Icons.NAV), 2),
                navItem("Olaylar", Icons.bell(Icons.NAV), 3),
                navItem("Ayarlar", Icons.sliders(Icons.NAV), 4));

        Button reboot = Ui.button("Yeniden başlat", Icons.power(Icons.SM), "subtle");
        reboot.setMaxWidth(Double.MAX_VALUE);
        reboot.setOnAction(e -> confirmReboot(reboot, events));

        VBox rail = new VBox(brandBox, items, Ui.vSpacer(),
                Ui.cardTitle("MODEM"), reboot);
        rail.getStyleClass().add("nav-rail");
        VBox.setMargin(reboot, new Insets(6, 0, 0, 0));
        return rail;
    }

    private HBox navItem(String label, Node icon, int index) {
        Label text = new Label(label);
        text.getStyleClass().add("nav-label");
        HBox item = new HBox(11, icon, text);
        item.getStyleClass().add("nav-item");
        item.setAlignment(Pos.CENTER_LEFT);
        item.setOnMouseClicked(e -> select(index));
        navItems.add(item);
        return item;
    }

    private void select(int index) {
        for (int i = 0; i < navItems.size(); i++) {
            navItems.get(i).getStyleClass().remove("active");
            if (i == index) navItems.get(i).getStyleClass().add("active");
        }
        pageTitle.setText(PAGE_NAMES[index]);
        Anim.swap(content, views.get(index));
    }

    // ---------- durum gostergeleri ----------

    private void updateStatus(LineSample s) {
        double snr = s.snrDown() / 10.0;
        String state = !s.isUp() ? "down" : snr < settings.snrThresholdDb() ? "warn" : "up";
        String label = switch (state) {
            case "down" -> "HAT KOPUK";
            case "warn" -> "DÜŞÜK SNR";
            default -> "HAT AKTİF";
        };
        Platform.runLater(() -> setStatus(state, label));
        setTray(state, s.isUp()
                ? "LineWatch — aktif · SNR " + Fmt.dec1(snr) + " dB · " + Fmt.count(s.crcDown()) + " CRC"
                : "LineWatch — HAT KOPUK");
    }

    private void setStatus(String state, String label) {
        Ui.setDotKind(statusDot, state);
        statusLabel.setText(label);
        statusLabel.getStyleClass().removeAll("up", "down", "warn");
        if (!"unknown".equals(state)) statusLabel.getStyleClass().add(state);
    }

    // ---------- modem yeniden baslatma ----------

    private void confirmReboot(Button trigger, EventRepository events) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Modem yeniden başlatılacak; internet 2-3 dakika kesilir. Emin misin?",
                ButtonType.YES, ButtonType.NO);
        confirm.setHeaderText("Modemi yeniden başlat");
        confirm.initOwner(stage);
        confirm.showAndWait().ifPresent(button -> {
            if (button != ButtonType.YES) return;
            trigger.setDisable(true);
            monitor.rebootModem(error -> Platform.runLater(() -> {
                trigger.setDisable(false);
                if (error == null) {
                    try {
                        events.insert(LineEvent.Type.REBOOT,
                                "Kullanıcı isteğiyle uygulamadan yeniden başlatıldı", 0);
                    } catch (Exception ignored) {
                        // olay kaydedilemese de reboot gonderildi; akisi bolmenin anlami yok
                    }
                    if (trayIcon != null) {
                        trayIcon.displayMessage("LineWatch",
                                "Reboot komutu gönderildi, modem yeniden başlıyor…",
                                TrayIcon.MessageType.INFO);
                    }
                } else {
                    Alert error2 = new Alert(Alert.AlertType.ERROR, "Reboot gönderilemedi: " + error);
                    error2.initOwner(stage);
                    error2.show();
                }
            }));
        });
    }

    private void applySettings() {
        applyThemePreference();
        monitor.restart(new TpLinkCollector(settings.modemHost(), settings.modemPassword()));
    }

    // ---------- tray ----------

    private void setupTray() {
        if (!SystemTray.isSupported()) return;
        java.awt.EventQueue.invokeLater(() -> {
            try {
                PopupMenu menu = new PopupMenu();
                MenuItem open = new MenuItem("Aç");
                open.addActionListener(e -> Platform.runLater(this::showWindow));
                MenuItem exit = new MenuItem("Çıkış");
                exit.addActionListener(e -> shutdown());
                menu.add(open);
                menu.addSeparator();
                menu.add(exit);

                trayIcon = new TrayIcon(trayImage("unknown"), "LineWatch", menu);
                trayIcon.setImageAutoSize(true);
                trayIcon.addActionListener(e -> Platform.runLater(this::showWindow));
                SystemTray.getSystemTray().add(trayIcon);
            } catch (Exception e) {
                System.err.println("Tray kurulamadi: " + e.getMessage());
            }
        });
    }

    private void showWindow() {
        stage.show();
        stage.toFront();
        stage.requestFocus();
    }

    /** Tray ikonunu duruma gore renklendirir ve ipucunu gunceller. */
    private void setTray(String state, String tooltip) {
        TrayIcon icon = trayIcon;
        if (icon == null) return;
        boolean changed = !state.equals(trayState);
        trayState = state;
        java.awt.EventQueue.invokeLater(() -> {
            if (changed) icon.setImage(trayImage(state));
            icon.setToolTip(tooltip);
        });
    }

    private static final Map<String, BufferedImage> TRAY_CACHE = new HashMap<>();

    /** Durum rengiyle doldurulmus daire icinde beyaz sinyal dalgasi. */
    private static synchronized BufferedImage trayImage(String state) {
        return TRAY_CACHE.computeIfAbsent(state, key -> {
            int size = 32;
            BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setColor(switch (key) {
                case "up" -> new java.awt.Color(0x3e, 0xcf, 0x8e);
                case "down" -> new java.awt.Color(0xe5, 0x48, 0x4d);
                case "warn" -> new java.awt.Color(0xe8, 0xa3, 0x3d);
                default -> new java.awt.Color(0x8a, 0x93, 0xa0);
            });
            g.fillOval(0, 0, size, size);
            g.setColor(java.awt.Color.WHITE);
            g.setStroke(new BasicStroke(3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawPolyline(new int[]{7, 12, 16, 20, 25}, new int[]{20, 12, 22, 12, 17}, 5);
            g.dispose();
            return image;
        });
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
