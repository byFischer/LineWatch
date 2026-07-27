package com.emre.linewatch.ui.widget;

import com.emre.linewatch.ui.Fmt;
import com.emre.linewatch.ui.Theme;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.function.Consumer;

/**
 * Son 24 saatin blok blok durumu. Her blok 15 dakika:
 * yesil = sorunsuz, kirmizi = kopma/sorun, bos = veri yok.
 */
public class UptimeStrip extends CanvasPane {

    public static final int UNKNOWN = 0;
    public static final int UP = 1;
    public static final int DOWN = 2;

    private static final double BAR_H = 26;
    private static final double LABEL_H = 16;
    private static final double GAP = 1.4;

    private int[] states = new int[0];
    private long from;
    private long blockMs;
    private int hover = -1;
    private Consumer<String> onHover;

    public UptimeStrip() {
        setPrefHeight(BAR_H + LABEL_H);
        setMinHeight(BAR_H + LABEL_H);

        setOnMouseMoved(e -> {
            int idx = indexAt(e.getX());
            if (idx != hover) {
                hover = idx;
                redraw();
                if (onHover != null) onHover.accept(describe(idx));
            }
        });
        setOnMouseExited(e -> {
            hover = -1;
            redraw();
            if (onHover != null) onHover.accept(null);
        });
    }

    /** Uzerine gelinen blogun aciklamasini alir; fare cikinca null gelir. */
    public void setOnHover(Consumer<String> onHover) {
        this.onHover = onHover;
    }

    public void setData(int[] states, long from, long blockMs) {
        this.states = states == null ? new int[0] : states;
        this.from = from;
        this.blockMs = blockMs;
        redraw();
    }

    private int indexAt(double x) {
        if (states.length == 0) return -1;
        double slot = getWidth() / states.length;
        int idx = (int) (x / slot);
        return idx < 0 || idx >= states.length ? -1 : idx;
    }

    private String describe(int idx) {
        if (idx < 0 || idx >= states.length) return null;
        long start = from + (long) idx * blockMs;
        String label = switch (states[idx]) {
            case UP -> "sorunsuz";
            case DOWN -> "kopma veya sorun";
            default -> "veri yok";
        };
        return Fmt.time(start) + " – " + Fmt.time(start + blockMs) + "   " + label;
    }

    @Override
    protected void draw(GraphicsContext g, double w, double h) {
        Theme.Palette p = palette();
        if (states.length == 0) {
            g.setFill(p.surfaceAlt());
            g.fillRoundRect(0, 0, w, BAR_H, 4, 4);
            return;
        }

        double slot = w / states.length;
        double barW = Math.max(1.5, slot - GAP);

        for (int i = 0; i < states.length; i++) {
            Color c = switch (states[i]) {
                case UP -> p.good();
                case DOWN -> p.bad();
                default -> p.surfaceAlt();
            };
            if (hover >= 0 && i != hover) c = c.deriveColor(0, 1, 1, 0.45);
            g.setFill(c);
            double x = i * slot;
            g.fillRoundRect(x, 0, barW, BAR_H, 3, 3);
        }

        if (hover >= 0) {
            g.setStroke(p.text());
            g.setLineWidth(1);
            double x = hover * slot + barW / 2;
            g.strokeLine(Math.round(x) + 0.5, 0, Math.round(x) + 0.5, BAR_H);
        }

        // saat etiketleri: 6 saatte bir + sagda "şimdi"
        g.setFont(Font.font("Segoe UI", 10.5));
        g.setFill(p.textFaint());
        long total = blockMs * states.length;
        int marks = 4;
        g.setTextAlign(TextAlignment.LEFT);
        for (int m = 0; m < marks; m++) {
            double frac = m / (double) marks;
            long ts = from + (long) (total * frac);
            g.fillText(Fmt.time(ts), frac * w, BAR_H + LABEL_H - 4);
        }
        g.setTextAlign(TextAlignment.RIGHT);
        g.fillText("şimdi", w, BAR_H + LABEL_H - 4);
        g.setTextAlign(TextAlignment.LEFT);
    }
}
