package com.emre.linewatch.ui;

import com.emre.linewatch.config.Settings;
import com.emre.linewatch.config.StartupManager;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/** Modem, ornekleme, esik, bildirim ve uygulama ayarlari. */
public class SettingsView extends ScrollPane {

    private final TextField host = new TextField();
    private final PasswordField password = new PasswordField();
    private final TextField poll = new TextField();
    private final TextField crcThreshold = new TextField();
    private final TextField snrThreshold = new TextField();
    private final TextField cooldown = new TextField();
    private final CheckBox toast = new CheckBox("Windows bildirimi (toast)");
    private final CheckBox telegram = new CheckBox("Telegram bildirimi");
    private final TextField tgToken = new TextField();
    private final TextField tgChatId = new TextField();
    private final CheckBox autoStart = new CheckBox("Bilgisayar acildiginda LineWatch otomatik baslasin");
    private final Label status = new Label();

    public SettingsView(Settings settings, Runnable onSaved) {
        host.setText(settings.modemHost());
        password.setText(settings.modemPassword());
        poll.setText(String.valueOf(settings.pollSeconds()));
        crcThreshold.setText(String.valueOf(settings.crcDeltaThreshold()));
        snrThreshold.setText(String.valueOf(settings.snrThresholdDb()));
        cooldown.setText(String.valueOf(settings.alertCooldownMinutes()));
        toast.setSelected(settings.toastEnabled());
        telegram.setSelected(settings.telegramEnabled());
        tgToken.setText(settings.telegramToken());
        tgChatId.setText(settings.telegramChatId());
        tgToken.setPromptText("BotFather'dan alinan token");
        tgChatId.setPromptText("chat_id (@userinfobot ile ogrenilebilir)");
        autoStart.setSelected(StartupManager.isEnabled());

        VBox root = new VBox(14,
                section("MODEM", grid(
                        row("Modem adresi", host),
                        row("Modem sifresi", password),
                        row("Ornekleme araligi (sn)", poll))),
                section("UYARI ESIKLERI", grid(
                        row("CRC artis esigi (ornekleme basina)", crcThreshold),
                        row("SNR esigi (dB)", snrThreshold),
                        row("Ayni bildirim icin bekleme (dk)", cooldown))),
                section("BILDIRIMLER", new VBox(10, toast, telegram,
                        grid(row("Telegram bot token", tgToken),
                             row("Telegram chat id", tgChatId)))),
                section("UYGULAMA", new VBox(10, autoStart)));

        Button save = new Button("Kaydet ve Uygula");
        save.setOnAction(e -> {
            try {
                settings.modemHost(host.getText().trim());
                settings.modemPassword(password.getText());
                settings.pollSeconds(parseInt(poll.getText(), 15));
                settings.crcDeltaThreshold(parseInt(crcThreshold.getText(), 50));
                settings.snrThresholdDb(parseDouble(snrThreshold.getText(), 6.0));
                settings.alertCooldownMinutes(parseInt(cooldown.getText(), 15));
                settings.toastEnabled(toast.isSelected());
                settings.telegramEnabled(telegram.isSelected());
                settings.telegramToken(tgToken.getText().trim());
                settings.telegramChatId(tgChatId.getText().trim());
                settings.save();
                StartupManager.setEnabled(autoStart.isSelected());
                status.setText("Kaydedildi, izleme yeniden baslatildi."
                        + (autoStart.isSelected() ? " Otomatik baslatma acik." : ""));
                onSaved.run();
            } catch (Exception ex) {
                status.setText("Kaydedilemedi: " + ex.getMessage());
            }
        });
        status.getStyleClass().add("muted");

        VBox page = new VBox(16, root, save, status);
        page.setPadding(new Insets(16));
        page.setMaxWidth(640);
        setContent(page);
        setFitToWidth(true);
    }

    private static VBox section(String title, javafx.scene.Node content) {
        Label t = new Label(title);
        t.getStyleClass().add("section-title");
        VBox card = new VBox(12, t, content);
        card.getStyleClass().add("card");
        return card;
    }

    private record Row(String label, javafx.scene.Node field) {}

    private static Row row(String label, javafx.scene.Node field) {
        return new Row(label, field);
    }

    private static GridPane grid(Row... rows) {
        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(10);
        for (int i = 0; i < rows.length; i++) {
            Label l = new Label(rows[i].label());
            l.getStyleClass().add("muted");
            g.add(l, 0, i);
            g.add(rows[i].field(), 1, i);
            if (rows[i].field() instanceof TextField tf) tf.setPrefWidth(280);
        }
        return g;
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static double parseDouble(String s, double def) {
        try { return Double.parseDouble(s.trim().replace(',', '.')); } catch (NumberFormatException e) { return def; }
    }
}
