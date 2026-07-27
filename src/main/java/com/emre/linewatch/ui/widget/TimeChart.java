package com.emre.linewatch.ui.widget;

import com.emre.linewatch.ui.Fmt;
import com.emre.linewatch.ui.Theme;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * Zaman serisi grafigi: alan dolgulu cizgiler, esik bandi, olay isaretleri
 * ve fare ile senkron crosshair + yuzen deger okuma kutusu.
 *
 * <p>JavaFX'in hazir LineChart'i yerine Canvas kullaniliyor: kategori ekseni
 * yerine gercek zaman ekseni, olay isaretleri ve crosshair bunlarla mumkun.
 */
public class TimeChart extends CanvasPane {

    /** Tek bir seri; {@code ts} artan sirada olmali. */
    public record Series(String name, Role role, long[] ts, double[] values, boolean fill) {
    }

    /** Zaman ekseni uzerinde dikey isaret (kopma, yeniden baslatma...). */
    public record Marker(long ts, String label, Role role) {
    }

    private static final double PAD_L = 52;
    private static final double PAD_R = 14;
    private static final double PAD_T = 12;

    private final List<Series> series = new ArrayList<>();
    private final List<Marker> markers = new ArrayList<>();
    private final Text probe = new Text();

    private String unit = "";
    private Double threshold;
    private String thresholdLabel;
    private boolean showTimeAxis;
    private boolean zeroBased;
    private long tFrom;
    private long tTo;

    private double crosshair = Double.NaN;
    private DoubleConsumer onCrosshair;

    public TimeChart() {
        setPrefHeight(150);
        setMinHeight(90);

        setOnMouseMoved(e -> {
            double w = getWidth();
            if (e.getX() < PAD_L || e.getX() > w - PAD_R || tTo <= tFrom) {
                apply(Double.NaN, true);
                return;
            }
            double frac = (e.getX() - PAD_L) / (w - PAD_L - PAD_R);
            apply(tFrom + frac * (tTo - tFrom), true);
        });
        setOnMouseExited(e -> apply(Double.NaN, true));
    }

    // ---------- yapilandirma ----------

    public TimeChart unit(String unit) {
        this.unit = unit == null ? "" : unit;
        return this;
    }

    public TimeChart threshold(Double value, String label) {
        this.threshold = value;
        this.thresholdLabel = label;
        redraw();
        return this;
    }

    public TimeChart timeAxis(boolean show) {
        this.showTimeAxis = show;
        redraw();
        return this;
    }

    /** Y ekseninin tabanini sifira sabitler (CRC artisi gibi sayimlar icin). */
    public TimeChart zeroBased(boolean zeroBased) {
        this.zeroBased = zeroBased;
        redraw();
        return this;
    }

    public void setRange(long from, long to) {
        this.tFrom = from;
        this.tTo = to;
        redraw();
    }

    public void setSeries(List<Series> list) {
        series.clear();
        if (list != null) series.addAll(list);
        redraw();
    }

    public void setMarkers(List<Marker> list) {
        markers.clear();
        if (list != null) markers.addAll(list);
        redraw();
    }

    // ---------- crosshair ----------

    /** Kardes grafiklerden gelen crosshair konumu; geri bildirim tetiklemez. */
    public void setCrosshair(double timestamp) {
        apply(timestamp, false);
    }

    public void setOnCrosshair(DoubleConsumer listener) {
        this.onCrosshair = listener;
    }

    private void apply(double t, boolean notify) {
        boolean changed = Double.isNaN(t) != Double.isNaN(crosshair)
                || (!Double.isNaN(t) && Math.abs(t - crosshair) > 1);
        crosshair = t;
        if (changed) redraw();
        if (notify && onCrosshair != null) onCrosshair.accept(t);
    }

    // ---------- cizim ----------

    @Override
    protected void draw(GraphicsContext g, double w, double h) {
        Theme.Palette p = palette();
        double padB = showTimeAxis ? 26 : 10;
        double plotW = w - PAD_L - PAD_R;
        double plotH = h - PAD_T - padB;
        if (plotW <= 10 || plotH <= 10) return;

        boolean hasData = series.stream().anyMatch(s -> s.values().length > 0);
        if (!hasData || tTo <= tFrom) {
            g.setFont(Font.font("Segoe UI", 12));
            g.setFill(p.textFaint());
            g.setTextAlign(TextAlignment.CENTER);
            g.setTextBaseline(VPos.CENTER);
            g.fillText("Bu aralıkta veri yok", w / 2, h / 2);
            g.setTextAlign(TextAlignment.LEFT);
            g.setTextBaseline(VPos.BASELINE);
            return;
        }

        // --- y ekseni araligi ---
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (Series s : series) {
            for (double v : s.values()) {
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        if (threshold != null) {
            min = Math.min(min, threshold);
            max = Math.max(max, threshold);
        }
        if (zeroBased) min = 0;
        if (max - min < 1e-9) {
            max += 1;
            min -= zeroBased ? 0 : 1;
        }
        double step = niceStep(max - min, 4);
        double yMin = zeroBased ? 0 : Math.floor(min / step) * step;
        double yMax = Math.ceil(max / step) * step;
        if (yMax - yMin < 1e-9) yMax = yMin + step;

        // --- yatay kilavuz + y etiketleri ---
        g.setFont(Font.font("Segoe UI", 10.5));
        g.setTextBaseline(VPos.CENTER);
        g.setLineWidth(1);
        for (double v = yMin; v <= yMax + 1e-9; v += step) {
            double y = Math.round(yTo(v, yMin, yMax, plotH)) + 0.5;
            g.setStroke(p.grid());
            g.strokeLine(PAD_L, y, w - PAD_R, y);
            g.setFill(p.textFaint());
            g.setTextAlign(TextAlignment.RIGHT);
            g.fillText(axisLabel(v, step), PAD_L - 9, y);
        }

        // --- esik bandi ---
        if (threshold != null && threshold > yMin) {
            double yTh = yTo(threshold, yMin, yMax, plotH);
            g.setFill(p.bad().deriveColor(0, 1, 1, 0.07));
            g.fillRect(PAD_L, yTh, plotW, PAD_T + plotH - yTh);
            g.setStroke(p.bad().deriveColor(0, 1, 1, 0.55));
            g.setLineDashes(4, 4);
            g.strokeLine(PAD_L, Math.round(yTh) + 0.5, w - PAD_R, Math.round(yTh) + 0.5);
            g.setLineDashes();
            if (thresholdLabel != null) {
                g.setFill(p.bad().deriveColor(0, 1, 1, 0.8));
                g.setTextAlign(TextAlignment.LEFT);
                g.setFont(Font.font("Segoe UI", 10));
                g.fillText(thresholdLabel, PAD_L + 6, yTh + 9);
                g.setFont(Font.font("Segoe UI", 10.5));
            }
        }

        // --- olay isaretleri ---
        // Uzun araliklarda (30 gun) yuzlerce kopma olabiliyor; her birini kesikli cizgi
        // ve nokta ile cizmek grafigi okunmaz hale getiriyor. Yogunken silik duz cizgiye
        // duserek baglami koruyoruz, ayrinti zaten alttaki olay seridinde.
        long visibleMarks = markers.stream().filter(m -> m.ts() >= tFrom && m.ts() <= tTo).count();
        boolean dense = visibleMarks > 25;
        g.setLineWidth(1);
        for (Marker m : markers) {
            if (m.ts() < tFrom || m.ts() > tTo) continue;
            double x = Math.round(xTo(m.ts(), plotW)) + 0.5;
            g.setStroke(color(p, m.role()).deriveColor(0, 1, 1, dense ? 0.14 : 0.5));
            if (!dense) g.setLineDashes(3, 3);
            g.strokeLine(x, PAD_T, x, PAD_T + plotH);
            g.setLineDashes();
            if (!dense) {
                g.setFill(color(p, m.role()));
                g.fillOval(x - 2.5, PAD_T - 2.5, 5, 5);
            }
        }

        // --- seriler ---
        g.setLineJoin(StrokeLineJoin.ROUND);
        g.setLineCap(StrokeLineCap.ROUND);
        for (Series s : series) {
            if (s.values().length == 0) continue;
            Color c = color(p, s.role());
            if (s.fill() && s.values().length > 1) {
                g.setFill(new LinearGradient(0, PAD_T, 0, PAD_T + plotH, false, CycleMethod.NO_CYCLE,
                        new Stop(0, c.deriveColor(0, 1, 1, 0.22)),
                        new Stop(1, c.deriveColor(0, 1, 1, 0.01))));
                g.beginPath();
                g.moveTo(xTo(s.ts()[0], plotW), PAD_T + plotH);
                for (int i = 0; i < s.values().length; i++) {
                    g.lineTo(xTo(s.ts()[i], plotW), yTo(s.values()[i], yMin, yMax, plotH));
                }
                g.lineTo(xTo(s.ts()[s.ts().length - 1], plotW), PAD_T + plotH);
                g.closePath();
                g.fill();
            }
            g.setStroke(c);
            g.setLineWidth(1.8);
            g.beginPath();
            for (int i = 0; i < s.values().length; i++) {
                double x = xTo(s.ts()[i], plotW);
                double y = yTo(s.values()[i], yMin, yMax, plotH);
                if (i == 0) g.moveTo(x, y);
                else g.lineTo(x, y);
            }
            g.stroke();
        }

        // --- zaman ekseni ---
        if (showTimeAxis) {
            g.setFont(Font.font("Segoe UI", 10.5));
            g.setFill(p.textFaint());
            g.setTextAlign(TextAlignment.CENTER);
            g.setTextBaseline(VPos.TOP);
            boolean longSpan = tTo - tFrom > 3L * 24 * 3600_000;
            for (int i = 0; i <= 4; i++) {
                long ts = tFrom + (long) ((tTo - tFrom) * (i / 4.0));
                double x = PAD_L + plotW * (i / 4.0);
                if (i == 0) g.setTextAlign(TextAlignment.LEFT);
                else if (i == 4) g.setTextAlign(TextAlignment.RIGHT);
                else g.setTextAlign(TextAlignment.CENTER);
                g.fillText(longSpan ? Fmt.dayShort(ts) : Fmt.time(ts), x, PAD_T + plotH + 8);
            }
            g.setTextBaseline(VPos.BASELINE);
            g.setTextAlign(TextAlignment.LEFT);
        }

        if (!Double.isNaN(crosshair)) drawCrosshair(g, p, w, plotW, plotH, yMin, yMax);
    }

    private void drawCrosshair(GraphicsContext g, Theme.Palette p,
                               double w, double plotW, double plotH, double yMin, double yMax) {
        long t = (long) crosshair;
        if (t < tFrom || t > tTo) return;
        double x = Math.round(xTo(t, plotW)) + 0.5;

        g.setStroke(p.textFaint().deriveColor(0, 1, 1, 0.6));
        g.setLineWidth(1);
        g.strokeLine(x, PAD_T, x, PAD_T + plotH);

        // okuma kutusu satirlari
        List<String> lines = new ArrayList<>();
        List<Color> dots = new ArrayList<>();
        long stamp = t;
        for (Series s : series) {
            int i = nearest(s.ts(), t);
            if (i < 0) continue;
            stamp = s.ts()[i];
            double y = yTo(s.values()[i], yMin, yMax, plotH);
            Color c = color(p, s.role());
            g.setFill(p.surface());
            g.fillOval(x - 4.2, y - 4.2, 8.4, 8.4);
            g.setFill(c);
            g.fillOval(x - 2.8, y - 2.8, 5.6, 5.6);
            lines.add(s.name() + "  " + Fmt.dec1(s.values()[i]) + (unit.isEmpty() ? "" : " " + unit));
            dots.add(c);
        }
        if (lines.isEmpty()) return;

        Font head = Font.font("Consolas", 11);
        Font body = Font.font("Segoe UI", 11.5);
        String title = Fmt.timeSec(stamp);

        probe.setFont(head);
        probe.setText(title);
        double boxW = probe.getLayoutBounds().getWidth();
        probe.setFont(body);
        for (String s : lines) {
            probe.setText(s);
            boxW = Math.max(boxW, probe.getLayoutBounds().getWidth() + 13);
        }
        boxW += 20;
        double rowH = 16;
        double boxH = 20 + lines.size() * rowH;

        double bx = x + 12;
        if (bx + boxW > w - PAD_R) bx = x - 12 - boxW;
        double by = PAD_T + 6;

        g.setFill(p.surfaceAlt().deriveColor(0, 1, 1, 0.97));
        g.fillRoundRect(bx, by, boxW, boxH, 8, 8);
        g.setStroke(p.border());
        g.setLineWidth(1);
        g.strokeRoundRect(bx, by, boxW, boxH, 8, 8);

        g.setTextBaseline(VPos.CENTER);
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(head);
        g.setFill(p.textFaint());
        g.fillText(title, bx + 10, by + 12);
        g.setFont(body);
        for (int i = 0; i < lines.size(); i++) {
            double ly = by + 22 + i * rowH + rowH / 2 - 2;
            g.setFill(dots.get(i));
            g.fillOval(bx + 10, ly - 3, 6, 6);
            g.setFill(p.text());
            g.fillText(lines.get(i), bx + 23, ly);
        }
        g.setTextBaseline(VPos.BASELINE);
    }

    // ---------- yardimcilar ----------

    private double xTo(long ts, double plotW) {
        return PAD_L + plotW * (ts - tFrom) / (double) (tTo - tFrom);
    }

    private double yTo(double v, double yMin, double yMax, double plotH) {
        return PAD_T + plotH - plotH * (v - yMin) / (yMax - yMin);
    }

    private String axisLabel(double v, double step) {
        if (step >= 1) return Fmt.count(Math.round(v));
        return Fmt.dec1(v);
    }

    private static double niceStep(double range, int targetTicks) {
        double raw = range / Math.max(1, targetTicks);
        if (raw <= 0) return 1;
        double mag = Math.pow(10, Math.floor(Math.log10(raw)));
        double norm = raw / mag;
        double factor = norm <= 1 ? 1 : norm <= 2 ? 2 : norm <= 5 ? 5 : 10;
        return factor * mag;
    }

    /** {@code ts} icinde {@code t}'ye en yakin indeks; dizi bos ise -1. */
    private static int nearest(long[] ts, long t) {
        if (ts.length == 0) return -1;
        int i = Arrays.binarySearch(ts, t);
        if (i >= 0) return i;
        int ins = -i - 1;
        if (ins == 0) return 0;
        if (ins >= ts.length) return ts.length - 1;
        return (t - ts[ins - 1] <= ts[ins] - t) ? ins - 1 : ins;
    }
}
