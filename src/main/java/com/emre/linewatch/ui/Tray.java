package com.emre.linewatch.ui;

import com.emre.linewatch.config.I18n;
import com.emre.linewatch.config.Log;
import javafx.application.Platform;

import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Sistem tepsisi simgesi: durum rengi, ipucu ve Ac / Cikis menusu.
 * AWT uzerinden calisir; JavaFX tarafina geri cagrilar Platform.runLater ile doner.
 */
public final class Tray {

    private static final Map<String, BufferedImage> CACHE = new HashMap<>();

    private volatile TrayIcon icon;
    private volatile String state = "unknown";

    /**
     * @param onOpen JavaFX thread'inde calisir (pencereyi ac)
     * @param onExit AWT thread'inde calisir (uygulamayi kapat)
     */
    public void install(Runnable onOpen, Runnable onExit) {
        if (!SystemTray.isSupported()) return;
        java.awt.EventQueue.invokeLater(() -> {
            try {
                PopupMenu menu = new PopupMenu();
                MenuItem open = new MenuItem(I18n.t("tray.open"));
                open.addActionListener(e -> Platform.runLater(onOpen));
                MenuItem exit = new MenuItem(I18n.t("tray.exit"));
                exit.addActionListener(e -> onExit.run());
                menu.add(open);
                menu.addSeparator();
                menu.add(exit);

                TrayIcon created = new TrayIcon(image("unknown"), "LineWatch", menu);
                created.setImageAutoSize(true);
                created.addActionListener(e -> Platform.runLater(onOpen));
                SystemTray.getSystemTray().add(created);
                icon = created;
            } catch (Exception e) {
                Log.get(Tray.class).log(Level.WARNING, "Tray kurulamadi", e);
            }
        });
    }

    /** Bildirimler (toast) icin; tray kurulamadiysa null. */
    public TrayIcon icon() {
        return icon;
    }

    /** Simgeyi duruma gore renklendirir ve ipucunu gunceller (herhangi bir thread). */
    public void set(String newState, String tooltip) {
        TrayIcon current = icon;
        if (current == null) return;
        boolean changed = !newState.equals(state);
        state = newState;
        java.awt.EventQueue.invokeLater(() -> {
            if (changed) current.setImage(image(newState));
            current.setToolTip(tooltip);
        });
    }

    public void remove() {
        TrayIcon current = icon;
        if (current != null) SystemTray.getSystemTray().remove(current);
    }

    /** Durum rengiyle doldurulmus daire icinde beyaz sinyal dalgasi. */
    private static synchronized BufferedImage image(String state) {
        return CACHE.computeIfAbsent(state, key -> {
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
}
