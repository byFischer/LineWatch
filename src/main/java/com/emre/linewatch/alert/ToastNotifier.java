package com.emre.linewatch.alert;

import java.awt.TrayIcon;
import java.util.function.Supplier;

/** Windows bildirim merkezine tray uzerinden toast gonderir. */
public class ToastNotifier implements Notifier {

    private final Supplier<TrayIcon> trayIcon;

    public ToastNotifier(Supplier<TrayIcon> trayIcon) {
        this.trayIcon = trayIcon;
    }

    @Override
    public void notify(String title, String message, boolean urgent) {
        TrayIcon icon = trayIcon.get();
        if (icon != null) {
            icon.displayMessage(title, message,
                    urgent ? TrayIcon.MessageType.WARNING : TrayIcon.MessageType.INFO);
        }
    }
}
