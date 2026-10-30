package com.emre.linewatch.ui;

import com.emre.linewatch.config.I18n;
import com.emre.linewatch.storage.EventRepository;
import com.emre.linewatch.storage.LineEvent;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

import java.awt.TrayIcon;
import java.util.function.Consumer;

/** Modemi yeniden baslatma: onay sorar, komutu gonderir, sonucu bildirir. */
public final class RebootDialog {

    private RebootDialog() {
    }

    /**
     * @param reboot yeniden baslatmayi baslatir; sonucu callback'e verir (null = basari)
     */
    public static void confirm(Window owner, Button trigger, Consumer<Consumer<String>> reboot,
                               EventRepository events, Tray tray) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                I18n.t("reboot.confirm"), ButtonType.YES, ButtonType.NO);
        confirm.setHeaderText(I18n.t("reboot.title"));
        confirm.initOwner(owner);
        confirm.showAndWait().ifPresent(button -> {
            if (button != ButtonType.YES) return;
            trigger.setDisable(true);
            reboot.accept(error -> Platform.runLater(() -> {
                trigger.setDisable(false);
                if (error == null) {
                    try {
                        events.insert(LineEvent.Type.REBOOT, I18n.t("reboot.eventDetail"), 0);
                    } catch (Exception ignored) {
                        // olay kaydedilemese de reboot gonderildi; akisi bolmenin anlami yok
                    }
                    TrayIcon icon = tray.icon();
                    if (icon != null) {
                        icon.displayMessage("LineWatch", I18n.t("reboot.sent"), TrayIcon.MessageType.INFO);
                    }
                } else {
                    Alert failed = new Alert(Alert.AlertType.ERROR, I18n.t("reboot.failed", error));
                    failed.initOwner(owner);
                    failed.show();
                }
            }));
        });
    }
}
