package com.emre.linewatch.ui;

import com.emre.linewatch.collector.LineSample;
import com.emre.linewatch.config.I18n;

import java.util.ArrayList;
import java.util.List;

/**
 * Hattin tek bakista okunan durumu: 0-100 puan, harf notu ve tek cumlelik teshis.
 *
 * <p>Puanlama agirliklari: SNR marji 30, son 24 saatteki kopmalar 30,
 * saatlik CRC artisi 25, hat kapasitesi kullanimi 15.
 */
public record HealthScore(int score, String grade, String headline, String detail, String state) {

    public static HealthScore unknown() {
        return new HealthScore(0, I18n.t("common.dash"), I18n.t("health.waitingHeadline"),
                I18n.t("health.waitingDetail"), "unknown");
    }

    public static HealthScore down(String reason) {
        return new HealthScore(0, "F", I18n.t("health.downHeadline"),
                reason == null || reason.isBlank() ? I18n.t("health.downDetail") : reason, "down");
    }

    /**
     * @param drops24h    son 24 saatteki LINK_DOWN sayisi
     * @param crcPerHour  son 24 saatteki ortalama saatlik CRC artisi
     */
    public static HealthScore of(LineSample s, int drops24h, double crcPerHour) {
        double snr = s.snrDown() / 10.0;
        double capacity = s.maxRateDown() > 0 ? 100.0 * s.syncDown() / s.maxRateDown() : 100;

        int points = snrPoints(snr) + dropPoints(drops24h) + crcPoints(crcPerHour) + capacityPoints(capacity);
        String grade = points >= 85 ? "A" : points >= 70 ? "B" : points >= 55 ? "C" : points >= 40 ? "D" : "F";
        String state = points >= 70 ? "up" : points >= 45 ? "warn" : "down";

        String headline = switch (grade) {
            case "A" -> I18n.t("health.gradeA");
            case "B" -> I18n.t("health.gradeB");
            case "C" -> I18n.t("health.gradeC");
            case "D" -> I18n.t("health.gradeD");
            default -> I18n.t("health.gradeF");
        };

        return new HealthScore(points, grade, headline,
                describe(drops24h, crcPerHour, snr, capacity), state);
    }

    private static String describe(int drops, double crcPerHour, double snr, double capacity) {
        List<String> issues = new ArrayList<>();
        if (drops > 0) issues.add(I18n.t("health.issueDrops", drops));
        if (crcPerHour >= 10) issues.add(I18n.t("health.issueCrc", Fmt.count(Math.round(crcPerHour))));
        if (snr < 9) issues.add(I18n.t("health.issueSnr", Fmt.dec1(snr)));
        if (capacity < 70) issues.add(I18n.t("health.issueCapacity", Fmt.pct0(capacity)));

        if (issues.isEmpty()) return I18n.t("health.allGood");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < issues.size(); i++) {
            String issue = issues.get(i);
            // Turkcede "i" -> "İ" oldugu icin buyuk harf donusumu locale'e duyarli olmali
            if (i == 0) sb.append(issue.substring(0, 1).toUpperCase(Fmt.locale())).append(issue.substring(1));
            else if (i == issues.size() - 1) sb.append(I18n.t("common.and")).append(' ').append(issue);
            else sb.append(I18n.t("common.listSep")).append(' ').append(issue);
        }
        return sb.append('.').toString();
    }

    private static int snrPoints(double snr) {
        if (snr >= 12) return 30;
        if (snr >= 9) return 24;
        if (snr >= 6) return 15;
        if (snr >= 3) return 7;
        return 0;
    }

    private static int dropPoints(int drops) {
        if (drops == 0) return 30;
        if (drops == 1) return 22;
        if (drops <= 3) return 14;
        if (drops <= 6) return 6;
        return 0;
    }

    private static int crcPoints(double perHour) {
        if (perHour < 1) return 25;
        if (perHour < 10) return 22;
        if (perHour < 100) return 15;
        if (perHour < 500) return 8;
        if (perHour < 2000) return 3;
        return 0;
    }

    private static int capacityPoints(double pct) {
        if (pct >= 90) return 15;
        if (pct >= 75) return 11;
        if (pct >= 60) return 7;
        if (pct >= 40) return 3;
        return 0;
    }
}
