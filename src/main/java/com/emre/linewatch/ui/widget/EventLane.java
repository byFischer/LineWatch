package com.emre.linewatch.ui.widget;

import com.emre.linewatch.ui.Fmt;
import com.emre.linewatch.ui.Theme;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * Grafiklerin altindaki olay seridi: ayni zaman ekseninde kopma, CRC patlamasi
 * ve yeniden baslatma isaretleri. Grafiklerle ayni crosshair'i paylasir.
 */
public class EventLane extends CanvasPane {

    private static final double PAD_L = 52;
    private static final double PAD_R = 14;
    private static final double DOT_Y = 16;

    private final List<TimeChart.Marker> marks = new ArrayList<>();
    private long tFrom;
    private long tTo;
    private double crosshair = Double.NaN;
    private DoubleConsumer onCrosshair;
    private Consumer<String> onHover;

    public EventLane() {
        setPrefHeight(44);
        setMinHeight(44);

        setOnMouseMoved(e -> {
            double w = getWidth();
            if (e.getX() < PAD_L || e.getX() > w - PAD_R || tTo <= tFrom) {
                notifyCrosshair(Double.NaN);
                return;
            }
            double frac = (e.getX() - PAD_L) / (w - PAD_L - PAD_R);
            notifyCrosshair(tFrom + frac * (tTo - tFrom));
            if (onHover != null) onHover.accept(nearestLabel(e.getX(), w));
        });
        setOnMouseExited(e -> {
            notifyCrosshair(Double.NaN);
            if (onHover != null) onHover.accept(null);
        });
    }

    public void setRange(long from, long to) {
        this.tFrom = from;
        this.tTo = to;
        redraw();
    }

    public void setMarks(List<TimeChart.Marker> list) {
        marks.clear();
        if (list != null) marks.addAll(list);
        redraw();
    }

    public void setOnCrosshair(DoubleConsumer listener) {
        this.onCrosshair = listener;
    }

    public void setOnHover(Consumer<String> listener) {
        this.onHover = listener;
    }

    public void setCrosshair(double timestamp) {
        crosshair = timestamp;
        redraw();
    }

    private void notifyCrosshair(double t) {
        crosshair = t;
        redraw();
        if (onCrosshair != null) onCrosshair.accept(t);
    }

    private String nearestLabel(double x, double w) {
        double plotW = w - PAD_L - PAD_R;
        TimeChart.Marker best = null;
        double bestDist = 9;
        for (TimeChart.Marker m : marks) {
            if (m.ts() < tFrom || m.ts() > tTo) continue;
            double mx = PAD_L + plotW * (m.ts() - tFrom) / (double) (tTo - tFrom);
            double d = Math.abs(mx - x);
            if (d < bestDist) {
                bestDist = d;
                best = m;
            }
        }
        return best == null ? null : Fmt.timeSec(best.ts()) + "  " + best.label();
    }

    @Override
    protected void draw(GraphicsContext g, double w, double h) {
        Theme.Palette p = palette();
        double plotW = w - PAD_L - PAD_R;
        if (plotW <= 10 || tTo <= tFrom) return;

        g.setFont(Font.font("Segoe UI", 10.5));
        g.setFill(p.textFaint());
        g.setTextAlign(TextAlignment.RIGHT);
        g.setTextBaseline(VPos.CENTER);
        g.fillText("Olaylar", PAD_L - 9, DOT_Y);
        g.setTextAlign(TextAlignment.LEFT);

        g.setStroke(p.grid());
        g.setLineWidth(1);
        g.strokeLine(PAD_L, Math.round(DOT_Y) + 0.5, w - PAD_R, Math.round(DOT_Y) + 0.5);

        if (marks.isEmpty()) {
            g.setFill(p.textFaint());
            g.fillText(com.emre.linewatch.config.I18n.t("widget.noEventsInRange"), PAD_L + 4, DOT_Y + 18);
        }

        // etiketleri ust uste bindirmemek icin son yazilan x'i takip et
        double lastLabelX = -1000;
        boolean longSpan = tTo - tFrom > 3L * 24 * 3600_000;
        g.setTextBaseline(VPos.TOP);
        for (TimeChart.Marker m : marks) {
            if (m.ts() < tFrom || m.ts() > tTo) continue;
            double x = PAD_L + plotW * (m.ts() - tFrom) / (double) (tTo - tFrom);
            Color c = color(p, m.role());

            g.setStroke(c.deriveColor(0, 1, 1, 0.45));
            g.strokeLine(Math.round(x) + 0.5, 0, Math.round(x) + 0.5, DOT_Y - 4);
            g.setFill(p.surface());
            g.fillOval(x - 4.5, DOT_Y - 4.5, 9, 9);
            g.setFill(c);
            g.fillOval(x - 3, DOT_Y - 3, 6, 6);

            if (x - lastLabelX > 66) {
                g.setFill(p.textFaint());
                g.setTextAlign(TextAlignment.CENTER);
                g.fillText(longSpan ? Fmt.dayShort(m.ts()) : Fmt.time(m.ts()), x, DOT_Y + 8);
                g.setTextAlign(TextAlignment.LEFT);
                lastLabelX = x;
            }
        }
        g.setTextBaseline(VPos.BASELINE);

        if (!Double.isNaN(crosshair) && crosshair >= tFrom && crosshair <= tTo) {
            double x = Math.round(PAD_L + plotW * (crosshair - tFrom) / (double) (tTo - tFrom)) + 0.5;
            g.setStroke(p.textFaint().deriveColor(0, 1, 1, 0.6));
            g.strokeLine(x, 0, x, DOT_Y - 4);
        }
    }
}
