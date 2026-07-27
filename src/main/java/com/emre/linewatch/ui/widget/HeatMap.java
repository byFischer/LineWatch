package com.emre.linewatch.ui.widget;

import com.emre.linewatch.ui.Fmt;
import com.emre.linewatch.ui.Theme;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.function.Consumer;

/**
 * 7x24 gun/saat isi haritasi, kenarlarda satir ve sutun toplam cubuklariyla.
 * Renk olcegi logaritmik: tek buyuk patlama diger tum hucreleri soldurmasin.
 */
public class HeatMap extends CanvasPane {

    private static final String[] DAYS = {"Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz"};
    private static final double GUTTER_L = 34;
    private static final double HEADER_T = 18;
    private static final double TOTALS_R = 54;
    private static final double TOTALS_B = 38;
    private static final double GAP = 2;

    private double[][] matrix = new double[7][24];
    private double max;
    private String unitLabel = "";
    private int hoverDay = -1;
    private int hoverHour = -1;
    private Consumer<String> onHover;

    public HeatMap() {
        setPrefHeight(260);
        setMinHeight(200);

        setOnMouseMoved(e -> {
            int[] cell = cellAt(e.getX(), e.getY());
            if (cell == null) {
                clearHover();
                return;
            }
            if (cell[0] != hoverDay || cell[1] != hoverHour) {
                hoverDay = cell[0];
                hoverHour = cell[1];
                redraw();
                if (onHover != null) onHover.accept(describe(hoverDay, hoverHour));
            }
        });
        setOnMouseExited(e -> clearHover());
    }

    public void setOnHover(Consumer<String> onHover) {
        this.onHover = onHover;
    }

    public void setData(double[][] matrix, double max, String unitLabel) {
        this.matrix = matrix;
        this.max = max;
        this.unitLabel = unitLabel == null ? "" : unitLabel;
        redraw();
    }

    private void clearHover() {
        if (hoverDay < 0 && hoverHour < 0) return;
        hoverDay = -1;
        hoverHour = -1;
        redraw();
        if (onHover != null) onHover.accept(null);
    }

    private String describe(int d, int hIdx) {
        return DAYS[d] + " " + String.format("%02d:00", hIdx) + " – "
                + String.format("%02d:00", (hIdx + 1) % 24) + "   "
                + Fmt.count(Math.round(matrix[d][hIdx])) + " " + unitLabel;
    }

    private int[] cellAt(double x, double y) {
        double cellsW = getWidth() - GUTTER_L - TOTALS_R;
        double cellsH = getHeight() - HEADER_T - TOTALS_B;
        if (cellsW <= 0 || cellsH <= 0) return null;
        if (x < GUTTER_L || x > GUTTER_L + cellsW || y < HEADER_T || y > HEADER_T + cellsH) return null;
        int hour = (int) ((x - GUTTER_L) / (cellsW / 24));
        int day = (int) ((y - HEADER_T) / (cellsH / 7));
        if (hour < 0 || hour > 23 || day < 0 || day > 6) return null;
        return new int[]{day, hour};
    }

    @Override
    protected void draw(GraphicsContext g, double w, double h) {
        Theme.Palette p = palette();
        double cellsW = w - GUTTER_L - TOTALS_R;
        double cellsH = h - HEADER_T - TOTALS_B;
        if (cellsW <= 20 || cellsH <= 20) return;

        double cw = cellsW / 24;
        double ch = cellsH / 7;

        g.setFont(Font.font("Segoe UI", 10));
        g.setTextBaseline(VPos.CENTER);

        // saat basliklari (3 saatte bir, kalabalik olmasin)
        g.setFill(p.textFaint());
        g.setTextAlign(TextAlignment.CENTER);
        for (int hour = 0; hour < 24; hour += 3) {
            g.fillText(String.format("%02d", hour), GUTTER_L + hour * cw + cw / 2, HEADER_T / 2 + 1);
        }

        // gun etiketleri
        g.setTextAlign(TextAlignment.LEFT);
        for (int d = 0; d < 7; d++) {
            g.fillText(DAYS[d], 0, HEADER_T + d * ch + ch / 2);
        }

        // hucreler
        double[] rowTotals = new double[7];
        double[] colTotals = new double[24];
        double logMax = Math.log1p(max);
        for (int d = 0; d < 7; d++) {
            for (int hour = 0; hour < 24; hour++) {
                double v = matrix[d][hour];
                rowTotals[d] += v;
                colTotals[hour] += v;
                double norm = logMax > 0 && v > 0 ? Math.log1p(v) / logMax : 0;
                Color c = ramp(p, norm);
                boolean dimmed = hoverDay >= 0 && (d != hoverDay && hour != hoverHour);
                if (dimmed) c = c.deriveColor(0, 1, 1, 0.5);
                g.setFill(c);
                g.fillRoundRect(GUTTER_L + hour * cw, HEADER_T + d * ch,
                        Math.max(1, cw - GAP), Math.max(1, ch - GAP), 3, 3);
            }
        }
        if (hoverDay >= 0) {
            g.setStroke(p.text());
            g.setLineWidth(1.4);
            g.strokeRoundRect(GUTTER_L + hoverHour * cw - 0.5, HEADER_T + hoverDay * ch - 0.5,
                    Math.max(1, cw - GAP) + 1, Math.max(1, ch - GAP) + 1, 4, 4);
        }

        // satir toplamlari (sagda yatay cubuklar)
        double maxRow = 0;
        for (double v : rowTotals) maxRow = Math.max(maxRow, v);
        double barsW = TOTALS_R - 10;
        for (int d = 0; d < 7; d++) {
            double bw = maxRow > 0 ? barsW * (rowTotals[d] / maxRow) : 0;
            g.setFill(p.surfaceAlt());
            g.fillRoundRect(GUTTER_L + cellsW + 8, HEADER_T + d * ch + ch / 2 - 4, barsW, 8, 3, 3);
            g.setFill(p.accent().deriveColor(0, 1, 1, 0.75));
            g.fillRoundRect(GUTTER_L + cellsW + 8, HEADER_T + d * ch + ch / 2 - 4, Math.max(0, bw), 8, 3, 3);
        }

        // sutun toplamlari (altta dikey cubuklar)
        double maxCol = 0;
        for (double v : colTotals) maxCol = Math.max(maxCol, v);
        double barsH = TOTALS_B - 16;
        double barsTop = HEADER_T + cellsH + 8;
        for (int hour = 0; hour < 24; hour++) {
            double bh = maxCol > 0 ? barsH * (colTotals[hour] / maxCol) : 0;
            g.setFill(p.surfaceAlt());
            g.fillRoundRect(GUTTER_L + hour * cw, barsTop, Math.max(1, cw - GAP), barsH, 2, 2);
            g.setFill(p.accent().deriveColor(0, 1, 1, 0.75));
            g.fillRoundRect(GUTTER_L + hour * cw, barsTop + barsH - bh,
                    Math.max(1, cw - GAP), Math.max(0, bh), 2, 2);
        }

        g.setTextBaseline(VPos.BASELINE);
        g.setTextAlign(TextAlignment.LEFT);
    }

    /** 0..1 -> tema zemininden vurgu rengine dogru rampa. */
    private static Color ramp(Theme.Palette p, double t) {
        return p.heatFrom().interpolate(p.heatTo(), Math.max(0, Math.min(1, t)));
    }

    /** Lejant icin ayni rampanin ornekleri. */
    public static Color rampSample(double t) {
        return ramp(Theme.palette(), t);
    }
}
