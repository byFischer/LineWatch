package com.emre.linewatch.ui;

import com.emre.linewatch.storage.LineEvent;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

/**
 * Cizgi ikonlar (24x24 kafes, Feather tarzi).
 *
 * <p>Disaridan ikon kutuphanesi eklemiyoruz; her ikon tek bir SVG path'i.
 * Renk ve cizgi kalinligi CSS'teki {@code .glyph} sinifindan gelir, bu yuzden
 * burada fill/stroke programatik olarak set EDILMEZ — set edilirse CSS ezilir.
 */
public final class Icons {

    public static final double NAV = 17;
    public static final double WIN = 15;
    public static final double SM = 14;
    public static final double MD = 16;

    // --- gezinme ---
    private static final String LAYOUT = "M3 3h18v18H3z M3 9h18 M9 21V9";
    private static final String ACTIVITY = "M22 12h-4l-3 9L9 3l-3 9H2";
    private static final String GRID = "M3 3h18v18H3z M3 9h18 M3 15h18 M9 3v18 M15 3v18";
    private static final String BELL = "M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9 M13.73 21a2 2 0 0 1-3.46 0";
    private static final String SLIDERS = "M4 21v-7 M4 10V3 M12 21v-9 M12 8V3 M20 21v-5 M20 12V3 "
            + "M1 14h6 M9 8h6 M17 16h6";

    // --- pencere dugmeleri ---
    private static final String MINIMIZE = "M5 12h14";
    private static final String MAXIMIZE = "M5 5h14v14H5z";
    private static final String RESTORE = "M8 8h11v11H8z M5 16V5h11";
    private static final String CLOSE = "M6 6l12 12 M18 6L6 18";

    // --- eylemler ve durumlar ---
    private static final String REFRESH = "M23 4v6h-6 M1 20v-6h6 M3.51 9a9 9 0 0 1 14.85-3.36L23 10 "
            + "M1 14l4.64 4.36A9 9 0 0 0 20.49 15";
    private static final String POWER = "M18.36 6.64a9 9 0 1 1-12.73 0 M12 2v10";
    private static final String SEARCH = "M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16z M21 21l-4.35-4.35";
    private static final String EYE = "M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z";
    private static final String EYE_OFF = "M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94 "
            + "M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19 "
            + "M14.12 14.12a3 3 0 1 1-4.24-4.24 M1 1l22 22";
    private static final String CHECK = "M20 6L9 17l-5-5";
    private static final String ALERT = "M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z "
            + "M12 9v4 M12 17h.01";
    private static final String WIFI_OFF = "M1 1l22 22 M16.72 11.06A10.94 10.94 0 0 1 19 12.55 "
            + "M5 12.55a10.94 10.94 0 0 1 5.17-2.39 M10.71 5.05A16 16 0 0 1 22.58 9 "
            + "M1.42 9a15.91 15.91 0 0 1 4.7-2.88 M8.53 16.11a6 6 0 0 1 6.95 0 M12 20h.01";
    private static final String ZAP = "M13 2L3 14h9l-1 8 10-12h-9l1-8z";
    private static final String CLOCK = "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20z M12 6v6l4 2";
    private static final String ARROW_UP = "M12 19V5 M5 12l7-7 7 7";
    private static final String ARROW_DOWN = "M12 5v14 M19 12l-7 7-7-7";
    private static final String MINUS = "M5 12h14";
    private static final String CHEVRON_RIGHT = "M9 18l6-6-6-6";
    private static final String PLUG = "M9 2v6 M15 2v6 M6 8h12v4a6 6 0 0 1-12 0z M12 18v4";

    private Icons() {
    }

    public static Node layout(double size)   { return icon(LAYOUT, size); }
    public static Node activity(double size) { return icon(ACTIVITY, size); }
    public static Node grid(double size)     { return icon(GRID, size); }
    public static Node bell(double size)     { return icon(BELL, size); }
    public static Node sliders(double size)  { return icon(SLIDERS, size); }

    public static Node minimize(double size) { return icon(MINIMIZE, size); }
    public static Node maximize(double size) { return icon(MAXIMIZE, size); }
    public static Node restore(double size)  { return icon(RESTORE, size); }
    public static Node close(double size)    { return icon(CLOSE, size); }

    public static Node refresh(double size)  { return icon(REFRESH, size); }
    public static Node power(double size)    { return icon(POWER, size); }
    public static Node search(double size)   { return icon(SEARCH, size); }
    public static Node eye(double size)      { return icon(EYE, size); }
    public static Node eyeOff(double size)   { return icon(EYE_OFF, size); }
    public static Node check(double size)    { return icon(CHECK, size); }
    public static Node alert(double size)    { return icon(ALERT, size); }
    public static Node wifiOff(double size)  { return icon(WIFI_OFF, size); }
    public static Node zap(double size)      { return icon(ZAP, size); }
    public static Node clock(double size)    { return icon(CLOCK, size); }
    public static Node arrowUp(double size)  { return icon(ARROW_UP, size); }
    public static Node arrowDown(double size){ return icon(ARROW_DOWN, size); }
    public static Node flat(double size)     { return icon(MINUS, size); }
    public static Node chevron(double size)  { return icon(CHEVRON_RIGHT, size); }
    public static Node plug(double size)     { return icon(PLUG, size); }

    /** Olay tipine karsilik gelen ikon, ciddiyet rengiyle boyanmis. */
    public static Node forEvent(LineEvent.Type type, double size) {
        return icon(pathFor(type), size, severity(type));
    }

    private static String pathFor(LineEvent.Type type) {
        return switch (type) {
            case LINK_DOWN, NET_DOWN -> WIFI_OFF;
            case LINK_UP, NET_UP -> CHECK;
            case CRC_BURST, PACKET_LOSS -> ZAP;
            case HIGH_LATENCY -> CLOCK;
            case LOW_SNR, COLLECTOR_ERROR -> ALERT;
            case RETRAIN -> REFRESH;
            case REBOOT -> POWER;
            case MODEM_UNREACHABLE -> PLUG;
        };
    }

    /** Olay tipinin ciddiyeti; renk secimi icin: "bad", "warn", "good" veya "info". */
    public static String severity(LineEvent.Type type) {
        return switch (type) {
            case LINK_DOWN, NET_DOWN, MODEM_UNREACHABLE -> "bad";
            case CRC_BURST, LOW_SNR, COLLECTOR_ERROR, HIGH_LATENCY, PACKET_LOSS -> "warn";
            case LINK_UP, NET_UP -> "good";
            case RETRAIN, REBOOT -> "info";
        };
    }

    /**
     * 24x24 path'i istenen boyuta olcekler. Olcek Group icinde uygulanir ki
     * yerlesim sinirlari da kucul, aksi halde her ikon 24px yer kaplar.
     *
     * @param extraGlyphClasses cizgi rengini degistiren ek sinifler ("good", "bad"...)
     */
    private static Node icon(String path, double size, String... extraGlyphClasses) {
        SVGPath svg = new SVGPath();
        svg.setContent(path);
        svg.getStyleClass().add("glyph");
        svg.getStyleClass().addAll(extraGlyphClasses);
        double scale = size / 24.0;
        svg.setScaleX(scale);
        svg.setScaleY(scale);

        StackPane holder = new StackPane(new Group(svg));
        holder.setMinSize(size, size);
        holder.setPrefSize(size, size);
        holder.setMaxSize(size, size);
        holder.setMouseTransparent(true);
        return holder;
    }
}
