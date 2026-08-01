package com.emre.linewatch.ui;

import com.emre.linewatch.collector.LineSample;
import com.emre.linewatch.collector.TpLinkCollector;
import com.emre.linewatch.config.I18n;
import com.emre.linewatch.config.Settings;
import com.emre.linewatch.config.StartupManager;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Modem, esik, bildirim, gorunum ve uygulama ayarlari. */
public class SettingsView extends ScrollPane {

    private final Settings settings;
    private final Runnable onSaved;

    private final TextField host = new TextField();
    private final PasswordField password = new PasswordField();
    private final TextField passwordPlain = new TextField();
    private final TextField poll = new TextField();
    private final TextField crcThreshold = new TextField();
    private final TextField snrThreshold = new TextField();
    private final TextField latencyThreshold = new TextField();
    private final TextField lossThreshold = new TextField();
    private final TextField cooldown = new TextField();
    private final CheckBox monitorModem = new CheckBox(I18n.t("settings.modemEnabled"));
    private final CheckBox monitorNet = new CheckBox(I18n.t("settings.netEnabled"));
    private final CheckBox toast = new CheckBox(I18n.t("settings.toast"));
    private final CheckBox telegram = new CheckBox(I18n.t("settings.telegram"));
    private final TextField tgToken = new TextField();
    private final TextField tgChatId = new TextField();
    private final CheckBox autoStart = new CheckBox(I18n.t("settings.autoStart"));
    private final ComboBox<Theme.Mode> themeBox = new ComboBox<>();
    private final ComboBox<Lang> languageBox = new ComboBox<>();

    private final Label testResult = Ui.faint("");
    private final Label saveStatus = Ui.faint("");
    private boolean passwordVisible;

    public SettingsView(Settings settings, Runnable onSaved) {
        this.settings = settings;
        this.onSaved = onSaved;

        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);

        load();

        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(14);
        for (int i = 0; i < 2; i++) {
            ColumnConstraints c = new ColumnConstraints();
            c.setPercentWidth(50);
            grid.getColumnConstraints().add(c);
        }

        add(grid, modemCard(), 0, 0);
        add(grid, thresholdCard(), 1, 0);
        add(grid, netCard(), 0, 1);
        add(grid, notificationCard(), 1, 1);
        add(grid, appearanceCard(), 0, 2);

        Button save = Ui.button(I18n.t("settings.save"), null);
        save.setOnAction(e -> save());
        HBox actions = Ui.row(12, save, saveStatus);

        VBox page = new VBox(16, grid, actions);
        page.getStyleClass().add("page");
        page.setMaxWidth(940);
        setContent(page);
    }

    private static void add(GridPane grid, VBox card, int col, int row) {
        card.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(card, Priority.ALWAYS);
        grid.add(card, col, row);
    }

    // ---------- kartlar ----------

    private VBox modemCard() {
        host.setPromptText("http://192.168.1.1");

        Button test = Ui.button(I18n.t("settings.testConnection"), Icons.plug(Icons.SM), "subtle");
        test.setOnAction(e -> testConnection(test));

        // Modem izleme kapaliyken alanlar soluklasir; uygulama internet olcumuyle
        // calismaya devam ettigi icin bu bir hata durumu degil, bilincli bir tercih.
        VBox fields = new VBox(12,
                field(I18n.t("settings.modemHost"), host),
                field(I18n.t("settings.modemPassword"), passwordField()),
                Ui.row(10, test, testResult));
        fields.disableProperty().bind(monitorModem.selectedProperty().not());

        return Ui.card(12,
                Ui.cardTitle(I18n.t("settings.modem")),
                monitorModem,
                fields,
                Ui.faint(I18n.t("settings.modemHint")));
    }

    private VBox netCard() {
        poll.setPromptText("15");
        return Ui.card(12,
                Ui.cardTitle(I18n.t("settings.net")),
                monitorNet,
                Ui.faint(I18n.t("settings.netHint")),
                field(I18n.t("settings.pollInterval"), withSuffix(poll, I18n.t("common.seconds"))),
                Ui.faint(I18n.t("settings.pollHint")));
    }

    private VBox thresholdCard() {
        return Ui.card(12,
                Ui.cardTitle(I18n.t("settings.thresholds")),
                field(I18n.t("settings.crcThreshold"), withSuffix(crcThreshold, I18n.t("settings.perSample"))),
                field(I18n.t("settings.snrThreshold"), withSuffix(snrThreshold, "dB")),
                field(I18n.t("settings.latencyThreshold"), withSuffix(latencyThreshold, "ms")),
                field(I18n.t("settings.lossThreshold"), withSuffix(lossThreshold, "%")),
                field(I18n.t("settings.cooldown"), withSuffix(cooldown, I18n.t("common.minutes"))),
                Ui.faint(I18n.t("settings.thresholdHint")));
    }

    private VBox notificationCard() {
        tgToken.setPromptText(I18n.t("settings.botTokenPrompt"));
        tgChatId.setPromptText(I18n.t("settings.chatIdPrompt"));
        tgToken.disableProperty().bind(telegram.selectedProperty().not());
        tgChatId.disableProperty().bind(telegram.selectedProperty().not());

        return Ui.card(12,
                Ui.cardTitle(I18n.t("settings.notifications")),
                toast,
                telegram,
                field(I18n.t("settings.botToken"), tgToken),
                field(I18n.t("settings.chatId"), tgChatId));
    }

    private VBox appearanceCard() {
        themeBox.getItems().setAll(Theme.Mode.values());
        themeBox.setValue(Theme.mode());
        themeBox.setButtonCell(themeCell());
        themeBox.setCellFactory(list -> themeCell());
        themeBox.setOnAction(e -> {
            Theme.Mode mode = themeBox.getValue();
            if (mode != null) Theme.setMode(mode);
        });

        languageBox.getItems().setAll(Lang.values());
        languageBox.setValue(Lang.of(settings.language()));
        languageBox.setButtonCell(languageCell());
        languageBox.setCellFactory(list -> languageCell());

        return Ui.card(12,
                Ui.cardTitle(I18n.t("settings.appearance")),
                field(I18n.t("settings.theme"), themeBox),
                Ui.faint(I18n.t("settings.themeHint")),
                field(I18n.t("settings.language"), languageBox),
                Ui.faint(I18n.t("settings.languageHint")),
                autoStart);
    }

    /**
     * Dil secenekleri. Ingilizce ve Turkce kendi adlariyla yazilir — kullanici
     * anlamadigi bir dile dusmusse secenegi yine de taniyabilsin diye.
     */
    private enum Lang {
        AUTO(I18n.AUTO), TR("tr"), EN("en");

        final String tag;

        Lang(String tag) { this.tag = tag; }

        static Lang of(String tag) {
            for (Lang l : values()) {
                if (l.tag.equalsIgnoreCase(tag)) return l;
            }
            return AUTO;
        }

        String label() {
            return switch (this) {
                case AUTO -> I18n.t("settings.languageAuto");
                case TR -> "Türkçe";
                case EN -> "English";
            };
        }
    }

    private static javafx.scene.control.ListCell<Lang> languageCell() {
        return new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(Lang item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.label());
            }
        };
    }

    private static javafx.scene.control.ListCell<Theme.Mode> themeCell() {
        return new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(Theme.Mode item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.label());
            }
        };
    }

    // ---------- alan kaliplari ----------

    private static VBox field(String label, Node control) {
        Label key = Ui.faint(label);
        if (control instanceof javafx.scene.layout.Region region) region.setMaxWidth(Double.MAX_VALUE);
        VBox box = new VBox(5, key, control);
        VBox.setVgrow(control, Priority.NEVER);
        return box;
    }

    private static HBox withSuffix(TextField field, String suffix) {
        field.setPrefColumnCount(6);
        HBox box = Ui.row(8, field, Ui.faint(suffix));
        HBox.setHgrow(field, Priority.ALWAYS);
        return box;
    }

    /** Sifre alani + goster/gizle dugmesi; iki alan ayni metni paylasir. */
    private HBox passwordField() {
        passwordPlain.textProperty().bindBidirectional(password.textProperty());
        passwordPlain.setVisible(false);
        passwordPlain.setManaged(false);

        StackPane stack = new StackPane(password, passwordPlain);
        HBox.setHgrow(stack, Priority.ALWAYS);

        Button toggle = Ui.iconButton(Icons.eye(Icons.SM), I18n.t("settings.showPassword"), "button", "ghost");
        toggle.setOnAction(e -> {
            passwordVisible = !passwordVisible;
            password.setVisible(!passwordVisible);
            password.setManaged(!passwordVisible);
            passwordPlain.setVisible(passwordVisible);
            passwordPlain.setManaged(passwordVisible);
            toggle.setGraphic(passwordVisible ? Icons.eyeOff(Icons.SM) : Icons.eye(Icons.SM));
            Ui.install(toggle, I18n.t(passwordVisible ? "settings.hidePassword" : "settings.showPassword"));
        });
        return Ui.row(6, stack, toggle);
    }

    // ---------- islemler ----------

    private void load() {
        host.setText(settings.modemHost());
        password.setText(settings.modemPassword());
        poll.setText(String.valueOf(settings.pollSeconds()));
        crcThreshold.setText(String.valueOf(settings.crcDeltaThreshold()));
        snrThreshold.setText(String.valueOf(settings.snrThresholdDb()));
        latencyThreshold.setText(String.valueOf(settings.latencyThresholdMs()));
        lossThreshold.setText(String.valueOf(settings.lossThresholdPct()));
        cooldown.setText(String.valueOf(settings.alertCooldownMinutes()));
        monitorModem.setSelected(settings.modemEnabled());
        monitorNet.setSelected(settings.netProbeEnabled());
        toast.setSelected(settings.toastEnabled());
        telegram.setSelected(settings.telegramEnabled());
        tgToken.setText(settings.telegramToken());
        tgChatId.setText(settings.telegramChatId());
        autoStart.setSelected(StartupManager.isEnabled());
    }

    private void testConnection(Button trigger) {
        trigger.setDisable(true);
        testResult.getStyleClass().setAll("faint");
        testResult.setText(I18n.t("settings.connecting"));
        String hostValue = host.getText().trim();
        String passValue = password.getText();

        Thread worker = new Thread(() -> {
            String message;
            String style;
            TpLinkCollector collector = null;
            try {
                collector = new TpLinkCollector(hostValue, passValue);
                LineSample sample = collector.collect();
                message = I18n.t("settings.testOk", sample.modulation(),
                        Fmt.dec1(sample.snrDown() / 10.0));
                style = "ok";
            } catch (Exception e) {
                message = I18n.t("settings.testFail", e.getMessage());
                style = "err";
            } finally {
                if (collector != null) collector.close();
            }
            String finalMessage = message;
            String finalStyle = style;
            Platform.runLater(() -> {
                trigger.setDisable(false);
                testResult.getStyleClass().setAll("faint");
                testResult.setStyle("-fx-text-fill: " + ("ok".equals(finalStyle)
                        ? hex(Theme.palette().good()) : hex(Theme.palette().bad())) + ";");
                testResult.setText(finalMessage);
            });
        }, "settings-conn-test");
        worker.setDaemon(true);
        worker.start();
    }

    private void save() {
        try {
            settings.modemHost(host.getText().trim());
            settings.modemPassword(password.getText());
            settings.pollSeconds(parseInt(poll.getText(), 15));
            settings.crcDeltaThreshold(parseInt(crcThreshold.getText(), 50));
            settings.snrThresholdDb(parseDouble(snrThreshold.getText(), 6.0));
            settings.latencyThresholdMs(parseInt(latencyThreshold.getText(), 150));
            settings.lossThresholdPct(parseInt(lossThreshold.getText(), 5));
            settings.alertCooldownMinutes(parseInt(cooldown.getText(), 15));
            settings.modemEnabled(monitorModem.isSelected());
            settings.netProbeEnabled(monitorNet.isSelected());
            settings.toastEnabled(toast.isSelected());
            settings.telegramEnabled(telegram.isSelected());
            settings.telegramToken(tgToken.getText().trim());
            settings.telegramChatId(tgChatId.getText().trim());
            settings.theme(themeBox.getValue() == null ? "AUTO" : themeBox.getValue().name());
            settings.language(languageBox.getValue() == null ? I18n.AUTO : languageBox.getValue().tag);
            settings.save();
            StartupManager.setEnabled(autoStart.isSelected());

            saveStatus.setStyle("-fx-text-fill: " + hex(Theme.palette().good()) + ";");
            String saved = I18n.t("settings.saved");
            saveStatus.setText(autoStart.isSelected() ? I18n.t("settings.savedAutoStart", saved) : saved);
            Anim.highlight(saveStatus);
            onSaved.run();
        } catch (Exception ex) {
            saveStatus.setStyle("-fx-text-fill: " + hex(Theme.palette().bad()) + ";");
            saveStatus.setText(I18n.t("settings.saveFailed", ex.getMessage()));
        }
    }

    private static String hex(javafx.scene.paint.Color c) {
        return String.format("#%02x%02x%02x",
                (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double parseDouble(String s, double def) {
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
