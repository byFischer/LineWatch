package com.emre.linewatch.report;

import com.emre.linewatch.config.I18n;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

/**
 * Rapor icin gomulu SVG grafikleri. Dis kutuphane yok: rapor tek bir HTML
 * dosyasi olarak acilip yazdirilabilmeli, internet gerektirmemeli.
 */
final class Svg {

    static final String BLUE = "#2563eb";
    static final String ORANGE = "#ea580c";
    static final String GRAY = "#64748b";
    static final String RED = "#dc2626";
    static final String PURPLE = "#7c3aed";

    private static final int W = 900, H = 220;
    private static final int LEFT = 52, RIGHT = 14, TOP = 12, BOTTOM = 28;
    private static final int PW = W - LEFT - RIGHT, PH = H - TOP - BOTTOM;

    private static final long HOUR = 3_600_000L;
    private static final long[] TICK_STEPS = {HOUR, 3 * HOUR, 6 * HOUR, 12 * HOUR, 24 * HOUR,
            48 * HOUR, 7 * 24 * HOUR, 14 * 24 * HOUR, 28 * 24 * HOUR};

    private Svg() {
    }

    /** Bir cizgi serisi. NaN degerler cizgide bosluk birakir. */
    record Series(String label, String color, long[] ts, double[] values, boolean dashed) {
    }

    /** Yatay referans cizgisi (esik, abonelik hizi). */
    record RefLine(String label, double value, String color) {
    }

    static String lineChart(long from, long to, List<Series> series, List<RefLine> refs,
                            String unit, ZoneId zone, long hourlyUntil) {
        double max = 0;
        for (Series s : series) for (double v : s.values()) if (!Double.isNaN(v)) max = Math.max(max, v);
        for (RefLine r : refs) max = Math.max(max, r.value());
        double top = niceMax(max);

        // Ardisik iki nokta arasinda bundan uzun bosluk varsa olcum yapilmamistir:
        // cizgiyi birlestirmek yaniltici olur.
        long maxGap = Math.max(3 * (to - from) / ReportBuilder.CHART_POINTS, 10 * 60_000L);
        // Saatlik ozet noktalari en az bir saat arayla gelir; arka arkaya iki saat birlesir.
        long hourlyGap = Math.max(maxGap, 90 * 60_000L);

        StringBuilder sb = open();
        grid(sb, from, to, top, unit, zone);
        for (RefLine r : refs) {
            double y = y(r.value(), top);
            sb.append(String.format(Locale.ROOT,
                    "<line x1='%d' x2='%d' y1='%.1f' y2='%.1f' stroke='%s' stroke-width='1.2' stroke-dasharray='6 4'/>",
                    LEFT, LEFT + PW, y, y, r.color()));
            sb.append(String.format(Locale.ROOT,
                    "<text x='%d' y='%.1f' text-anchor='end' class='ref' fill='%s'>%s</text>",
                    LEFT + PW - 4, y - 4, r.color(), HtmlReport.esc(r.label())));
        }
        for (Series s : series) {
            StringBuilder d = new StringBuilder();
            boolean penDown = false;
            long lastTs = Long.MIN_VALUE;
            for (int i = 0; i < s.ts().length; i++) {
                double v = s.values()[i];
                if (Double.isNaN(v)) {
                    penDown = false;
                    continue;
                }
                if (penDown && s.ts()[i] - lastTs > (lastTs < hourlyUntil ? hourlyGap : maxGap)) penDown = false;
                d.append(penDown ? 'L' : 'M')
                        .append(String.format(Locale.ROOT, "%.1f %.1f ", x(s.ts()[i], from, to), y(v, top)));
                penDown = true;
                lastTs = s.ts()[i];
            }
            sb.append("<path fill='none' stroke-linejoin='round' stroke-width='1.4' stroke='")
                    .append(s.color()).append("' d='").append(d).append('\'')
                    .append(s.dashed() ? " stroke-dasharray='5 3'" : "").append("/>");
        }
        return close(sb);
    }

    /** Zamana yayilmis cubuklar (orn. her aralikta olusan CRC). */
    static String timeBars(long from, long to, long[] ts, double[] values, String color, String unit, ZoneId zone) {
        double max = 0;
        for (double v : values) max = Math.max(max, v);
        double top = niceMax(max);
        StringBuilder sb = open();
        grid(sb, from, to, top, unit, zone);
        double barW = Math.max(1.0, (double) PW / Math.max(1, ReportBuilder.CHART_POINTS) * 0.9);
        for (int i = 0; i < ts.length; i++) {
            if (values[i] <= 0) continue;
            double yy = y(values[i], top);
            sb.append(String.format(Locale.ROOT,
                    "<rect x='%.1f' y='%.1f' width='%.1f' height='%.1f' fill='%s'/>",
                    x(ts[i], from, to) - barW / 2, yy, barW, TOP + PH - yy, color));
        }
        return close(sb);
    }

    /** Gunun 24 saatine gore dagilim. */
    static String hourBars(double[] values, String color, String unit) {
        double max = 0;
        for (double v : values) max = Math.max(max, v);
        double top = niceMax(max);
        StringBuilder sb = open();
        yAxis(sb, top, unit);
        double slot = (double) PW / 24;
        for (int h = 0; h < 24; h++) {
            double cx = LEFT + slot * h + slot / 2;
            if (values[h] > 0) {
                double yy = y(values[h], top);
                sb.append(String.format(Locale.ROOT,
                        "<rect x='%.1f' y='%.1f' width='%.1f' height='%.1f' rx='2' fill='%s'><title>%02d:00 — %s</title></rect>",
                        cx - slot * 0.35, yy, slot * 0.7, TOP + PH - yy, color, h, HtmlReport.num(values[h], 0)));
            }
            sb.append(String.format(Locale.ROOT, "<text x='%.1f' y='%d' text-anchor='middle'>%02d</text>",
                    cx, H - 8, h));
        }
        return close(sb);
    }

    // ---------- ortak parcalar ----------

    private static StringBuilder open() {
        return new StringBuilder(4096).append(String.format(Locale.ROOT,
                "<svg viewBox='0 0 %d %d' class='chart' role='img' xmlns='http://www.w3.org/2000/svg'>", W, H));
    }

    private static String close(StringBuilder sb) {
        return sb.append("</svg>").toString();
    }

    private static void grid(StringBuilder sb, long from, long to, double top, String unit, ZoneId zone) {
        yAxis(sb, top, unit);
        long span = to - from;
        long step = TICK_STEPS[TICK_STEPS.length - 1];
        for (long candidate : TICK_STEPS) {
            if (span / candidate <= 9) {
                step = candidate;
                break;
            }
        }
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern(step < 24 * HOUR ? "dd.MM HH:mm" : "dd.MM",
                I18n.locale());
        // Tikler yerel gece yarisina / tam saate hizalanir ki etiketler okunakli olsun.
        ZonedDateTime t = Instant.ofEpochMilli(from).atZone(zone).truncatedTo(ChronoUnit.HOURS);
        if (step >= 24 * HOUR) t = t.truncatedTo(ChronoUnit.DAYS);
        while (t.toInstant().toEpochMilli() < from) t = t.plusNanos(step * 1_000_000L);
        for (; t.toInstant().toEpochMilli() <= to; t = t.plusNanos(step * 1_000_000L)) {
            double xx = x(t.toInstant().toEpochMilli(), from, to);
            sb.append(String.format(Locale.ROOT,
                    "<line x1='%.1f' x2='%.1f' y1='%d' y2='%d' class='grid'/>", xx, xx, TOP, TOP + PH));
            sb.append(String.format(Locale.ROOT, "<text x='%.1f' y='%d' text-anchor='middle'>%s</text>",
                    xx, H - 8, fmt.format(t)));
        }
    }

    private static void yAxis(StringBuilder sb, double top, String unit) {
        for (int i = 0; i <= 4; i++) {
            double v = top * i / 4;
            double yy = y(v, top);
            sb.append(String.format(Locale.ROOT,
                    "<line x1='%d' x2='%d' y1='%.1f' y2='%.1f' class='%s'/>",
                    LEFT, LEFT + PW, yy, yy, i == 0 ? "axis" : "grid"));
            sb.append(String.format(Locale.ROOT, "<text x='%d' y='%.1f' text-anchor='end'>%s</text>",
                    LEFT - 6, yy + 4, HtmlReport.num(v, top < 4 ? 1 : 0)));
        }
        sb.append(String.format(Locale.ROOT, "<text x='%d' y='%d' class='unit'>%s</text>",
                LEFT + 4, TOP + 10, HtmlReport.esc(unit)));
    }

    private static double x(long ts, long from, long to) {
        return LEFT + PW * (double) (ts - from) / Math.max(1, to - from);
    }

    private static double y(double v, double top) {
        return TOP + PH - PH * Math.max(0, Math.min(v, top)) / top;
    }

    /** Eksen ust siniri: verinin biraz ustundeki 1/2/2.5/5 x 10^n degeri. */
    static double niceMax(double max) {
        if (max <= 0 || Double.isNaN(max)) return 1;
        double raw = max * 1.08;
        double mag = Math.pow(10, Math.floor(Math.log10(raw)));
        for (double m : new double[]{1, 2, 2.5, 5, 10}) {
            if (m * mag >= raw) return m * mag;
        }
        return 10 * mag;
    }
}
