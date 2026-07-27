package com.emre.linewatch.ui.widget;

import com.emre.linewatch.ui.Theme;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;

/** Metrik kartlarindaki kucuk trend cizgisi; cizgi ya da cubuk modunda. */
public class Sparkline extends CanvasPane {

    private static final double PAD = 3;

    private double[] values = new double[0];
    private Role role = Role.ACCENT;
    private final boolean bars;

    public Sparkline() {
        this(false);
    }

    public Sparkline(boolean bars) {
        this.bars = bars;
        setPrefHeight(32);
        setMinHeight(20);
    }

    public Sparkline role(Role r) {
        this.role = r;
        redraw();
        return this;
    }

    public void setValues(double[] v) {
        this.values = v == null ? new double[0] : v;
        redraw();
    }

    public boolean isEmpty() {
        return values.length < 2;
    }

    @Override
    protected void draw(GraphicsContext g, double w, double h) {
        Theme.Palette p = palette();
        if (values.length == 0) return;

        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double v : values) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        // cubuk modunda taban her zaman sifir: CRC artisi 0'dan olculur
        if (bars) min = 0;
        if (max - min < 1e-9) {
            min -= 0.5;
            max += 0.5;
        }

        Color c = color(p, role);
        double plotH = h - 2 * PAD;
        double range = max - min;

        if (bars) {
            double slot = w / values.length;
            double barW = Math.max(1.5, slot - 1.2);
            g.setFill(c.deriveColor(0, 1, 1, 0.85));
            for (int i = 0; i < values.length; i++) {
                double norm = (values[i] - min) / range;
                double bh = Math.max(values[i] > 0 ? 1.5 : 0, norm * plotH);
                g.fillRect(i * slot + (slot - barW) / 2, h - PAD - bh, barW, bh);
            }
            return;
        }

        if (values.length < 2) return;
        double stepX = w / (values.length - 1);

        // alan dolgusu: renkten seffafa
        g.setFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, c.deriveColor(0, 1, 1, 0.26)),
                new Stop(1, c.deriveColor(0, 1, 1, 0.0))));
        g.beginPath();
        g.moveTo(0, h);
        for (int i = 0; i < values.length; i++) {
            g.lineTo(i * stepX, yOf(values[i], min, range, h, plotH));
        }
        g.lineTo(w, h);
        g.closePath();
        g.fill();

        g.setStroke(c);
        g.setLineWidth(1.7);
        g.setLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);
        g.setLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        g.beginPath();
        for (int i = 0; i < values.length; i++) {
            double x = i * stepX;
            double y = yOf(values[i], min, range, h, plotH);
            if (i == 0) g.moveTo(x, y);
            else g.lineTo(x, y);
        }
        g.stroke();

        // son noktayi vurgula
        double lastX = w - 1.5;
        double lastY = yOf(values[values.length - 1], min, range, h, plotH);
        g.setFill(p.surface());
        g.fillOval(lastX - 3.2, lastY - 3.2, 6.4, 6.4);
        g.setFill(c);
        g.fillOval(lastX - 2.1, lastY - 2.1, 4.2, 4.2);
    }

    private static double yOf(double value, double min, double range, double h, double plotH) {
        return h - PAD - ((value - min) / range) * plotH;
    }
}
