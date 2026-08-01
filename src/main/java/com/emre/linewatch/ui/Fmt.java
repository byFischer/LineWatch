package com.emre.linewatch.ui;

import com.emre.linewatch.config.I18n;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Sayi ve tarih bicimleme. Ondalik/binlik ayraci ve gun-ay adlari secili dile
 * bagli oldugu icin bicimleyiciler dil degistiginde yeniden kuruluyor.
 *
 * <p>String.format kullaniliyor cunku DecimalFormat thread-safe degil; burada
 * arka plan is parcaciklari da bicimleme yapabiliyor.
 */
public final class Fmt {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private static volatile Locale current;
    private static volatile DateTimeFormatter fTime;
    private static volatile DateTimeFormatter fTimeSec;
    private static volatile DateTimeFormatter fDate;
    private static volatile DateTimeFormatter fDateTime;
    private static volatile DateTimeFormatter fDayLong;
    private static volatile DateTimeFormatter fDayShort;

    static {
        rebuild(I18n.locale());
    }

    private Fmt() {
    }

    /** Secili dilin locale'i; kucuk/buyuk harf donusumu de bununla yapilmali. */
    public static Locale locale() {
        return I18n.locale();
    }

    /** Bicimleyiciler eski dile aitse yeniden kurar. Cagri basina tek volatile okuma. */
    private static void ensure() {
        Locale now = I18n.locale();
        if (!now.equals(current)) rebuild(now);
    }

    private static synchronized void rebuild(Locale now) {
        if (now.equals(current)) return;
        boolean tr = "tr".equals(now.getLanguage());
        fTime = DateTimeFormatter.ofPattern("HH:mm", now).withZone(ZONE);
        fTimeSec = DateTimeFormatter.ofPattern("HH:mm:ss", now).withZone(ZONE);
        fDate = DateTimeFormatter.ofPattern(tr ? "dd.MM.yyyy" : "dd/MM/yyyy", now).withZone(ZONE);
        fDateTime = DateTimeFormatter.ofPattern(
                tr ? "dd.MM.yyyy HH:mm:ss" : "dd/MM/yyyy HH:mm:ss", now).withZone(ZONE);
        fDayLong = DateTimeFormatter.ofPattern(tr ? "d MMMM EEEE" : "EEEE d MMMM", now).withZone(ZONE);
        fDayShort = DateTimeFormatter.ofPattern("d MMM", now).withZone(ZONE);
        current = now;
    }

    // ---------- sayilar ----------

    /** 431204 -> "431.204" (tr) / "431,204" (en) */
    public static String count(long v) {
        return String.format(locale(), "%,d", v);
    }

    /** 11.75 -> "11,8" (tr) / "11.8" (en) */
    public static String dec1(double v) {
        return String.format(locale(), "%,.1f", v);
    }

    /** 149.6 -> "150" (ondaligi anlamsiz olan degerler icin) */
    public static String dec0(double v) {
        return String.format(locale(), "%,.0f", v);
    }

    public static String pair1(double a, double b) {
        return dec1(a) + " / " + dec1(b);
    }

    /** 12 -> "+12", -3 -> "-3" */
    public static String signed(long v) {
        return v > 0 ? "+" + count(v) : count(v);
    }

    /** Yuzde isaretinin yeri dile gore degisir: "%98,2" (tr) / "98.2%" (en). */
    public static String pct1(double v) {
        return I18n.t("fmt.percent", dec1(v));
    }

    public static String pct0(double v) {
        return I18n.t("fmt.percent", dec0(v));
    }

    // ---------- zaman ----------

    public static String time(long ts) {
        ensure();
        return fTime.format(Instant.ofEpochMilli(ts));
    }

    public static String timeSec(long ts) {
        ensure();
        return fTimeSec.format(Instant.ofEpochMilli(ts));
    }

    public static String date(long ts) {
        ensure();
        return fDate.format(Instant.ofEpochMilli(ts));
    }

    public static String dateTime(long ts) {
        ensure();
        return fDateTime.format(Instant.ofEpochMilli(ts));
    }

    public static String dayShort(long ts) {
        ensure();
        return fDayShort.format(Instant.ofEpochMilli(ts));
    }

    /** Zaman cizelgesi gun basligi: "Bugün", "Dün" ya da "26 Temmuz Pazar". */
    public static String dayHeader(long ts) {
        ensure();
        LocalDate day = Instant.ofEpochMilli(ts).atZone(ZONE).toLocalDate();
        LocalDate today = LocalDate.now(ZONE);
        if (day.equals(today)) return I18n.t("fmt.today");
        if (day.equals(today.minusDays(1))) return I18n.t("fmt.yesterday");
        return fDayLong.format(Instant.ofEpochMilli(ts));
    }

    /** 47 -> "47 sn", 131 -> "2 dk 11 sn", 3840 -> "1 sa 4 dk". 0/negatif -> "". */
    public static String duration(long seconds) {
        if (seconds <= 0) return "";
        if (seconds < 60) return I18n.t("fmt.durSeconds", seconds);
        if (seconds < 3600) {
            long m = seconds / 60, s = seconds % 60;
            return s == 0 ? I18n.t("fmt.durMinutes", m) : I18n.t("fmt.durMinutesSeconds", m, s);
        }
        long h = seconds / 3600, m = (seconds % 3600) / 60;
        return m == 0 ? I18n.t("fmt.durHours", h) : I18n.t("fmt.durHoursMinutes", h, m);
    }

    /** Kesintisiz sure: "3 gün 14 saat", "6 saat 12 dk", "8 dk". */
    public static String uptime(long millis) {
        if (millis <= 0) return I18n.t("common.dash");
        long totalMin = millis / 60_000;
        long days = totalMin / 1440;
        long hours = (totalMin % 1440) / 60;
        long mins = totalMin % 60;
        if (days > 0) {
            return hours == 0 ? I18n.t("fmt.upDays", days) : I18n.t("fmt.upDaysHours", days, hours);
        }
        if (hours > 0) {
            return mins == 0 ? I18n.t("fmt.upHours", hours) : I18n.t("fmt.upHoursMinutes", hours, mins);
        }
        return I18n.t("fmt.upMinutes", mins);
    }

    private static final String[] DAY_KEYS =
            {"day.mon", "day.tue", "day.wed", "day.thu", "day.fri", "day.sat", "day.sun"};

    /** Kisa gun adi; 0 = Pazartesi (isi haritasi ve analiz listesi icin). */
    public static String dayShortName(int index) {
        return I18n.t(DAY_KEYS[Math.floorMod(index, 7)]);
    }

    /** "4 sn önce", "2 dk önce", "1 sa önce". */
    public static String ago(long ts) {
        long sec = Math.max(0, (System.currentTimeMillis() - ts) / 1000);
        if (sec < 10) return I18n.t("fmt.agoJustNow");
        if (sec < 60) return I18n.t("fmt.agoSeconds", sec);
        if (sec < 3600) return I18n.t("fmt.agoMinutes", sec / 60);
        if (sec < 86400) return I18n.t("fmt.agoHours", sec / 3600);
        return I18n.t("fmt.agoDays", sec / 86400);
    }
}
