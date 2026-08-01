package com.emre.linewatch.ui;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Turkce bicimleme yardimcilari: binlik ayraci nokta, ondalik ayraci virgul.
 * String.format kullaniliyor cunku DecimalFormat thread-safe degil; burada
 * arka plan is parcaciklari da bicimleme yapabiliyor.
 */
public final class Fmt {

    public static final Locale TR = Locale.forLanguageTag("tr-TR");
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", TR).withZone(ZONE);
    private static final DateTimeFormatter TIME_SEC = DateTimeFormatter.ofPattern("HH:mm:ss", TR).withZone(ZONE);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy", TR).withZone(ZONE);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", TR).withZone(ZONE);
    private static final DateTimeFormatter DAY_LONG = DateTimeFormatter.ofPattern("d MMMM EEEE", TR).withZone(ZONE);
    private static final DateTimeFormatter DAY_SHORT = DateTimeFormatter.ofPattern("d MMM", TR).withZone(ZONE);

    private Fmt() {
    }

    // ---------- sayilar ----------

    /** 431204 -> "431.204" */
    public static String count(long v) {
        return String.format(TR, "%,d", v);
    }

    /** 11.75 -> "11,8" */
    public static String dec1(double v) {
        return String.format(TR, "%,.1f", v);
    }

    /** 149.6 -> "150" (gecikme gibi ondaligi anlamsiz olan degerler icin) */
    public static String dec0(double v) {
        return String.format(TR, "%,.0f", v);
    }

    /** 11.75, 6.2 -> "11,8 / 6,2" */
    public static String pair1(double a, double b) {
        return dec1(a) + " / " + dec1(b);
    }

    /** 12 -> "+12", -3 -> "-3", 0 -> "0" */
    public static String signed(long v) {
        return v > 0 ? "+" + count(v) : count(v);
    }

    /** 98.23 -> "%98,2" */
    public static String pct1(double v) {
        return "%" + dec1(v);
    }

    /** 62.4 -> "%62" */
    public static String pct0(double v) {
        return "%" + String.format(TR, "%.0f", v);
    }

    // ---------- zaman ----------

    public static String time(long ts)     { return TIME.format(Instant.ofEpochMilli(ts)); }
    public static String timeSec(long ts)  { return TIME_SEC.format(Instant.ofEpochMilli(ts)); }
    public static String date(long ts)     { return DATE.format(Instant.ofEpochMilli(ts)); }
    public static String dateTime(long ts) { return DATE_TIME.format(Instant.ofEpochMilli(ts)); }
    public static String dayShort(long ts) { return DAY_SHORT.format(Instant.ofEpochMilli(ts)); }

    /** Zaman cizelgesi gun basligi: "Bugün", "Dün" ya da "26 Temmuz Pazar". */
    public static String dayHeader(long ts) {
        LocalDate day = Instant.ofEpochMilli(ts).atZone(ZONE).toLocalDate();
        LocalDate today = LocalDate.now(ZONE);
        if (day.equals(today)) return "Bugün";
        if (day.equals(today.minusDays(1))) return "Dün";
        return DAY_LONG.format(Instant.ofEpochMilli(ts));
    }

    /** 47 -> "47 sn", 131 -> "2 dk 11 sn", 3840 -> "1 sa 4 dk". 0/negatif -> "". */
    public static String duration(long seconds) {
        if (seconds <= 0) return "";
        if (seconds < 60) return seconds + " sn";
        if (seconds < 3600) {
            long m = seconds / 60, s = seconds % 60;
            return s == 0 ? m + " dk" : m + " dk " + s + " sn";
        }
        long h = seconds / 3600, m = (seconds % 3600) / 60;
        return m == 0 ? h + " sa" : h + " sa " + m + " dk";
    }

    /** Kesintisiz sure: "3 gün 14 saat", "6 saat 12 dk", "8 dk". */
    public static String uptime(long millis) {
        if (millis <= 0) return "—";
        long totalMin = millis / 60_000;
        long days = totalMin / 1440;
        long hours = (totalMin % 1440) / 60;
        long mins = totalMin % 60;
        if (days > 0) return hours == 0 ? days + " gün" : days + " gün " + hours + " saat";
        if (hours > 0) return mins == 0 ? hours + " saat" : hours + " saat " + mins + " dk";
        return mins + " dk";
    }

    /** "4 sn önce", "2 dk önce", "1 sa önce". */
    public static String ago(long ts) {
        long sec = Math.max(0, (System.currentTimeMillis() - ts) / 1000);
        if (sec < 10) return "az önce";
        if (sec < 60) return sec + " sn önce";
        if (sec < 3600) return (sec / 60) + " dk önce";
        if (sec < 86400) return (sec / 3600) + " sa önce";
        return (sec / 86400) + " gün önce";
    }
}
