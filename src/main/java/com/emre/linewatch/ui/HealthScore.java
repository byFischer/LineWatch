package com.emre.linewatch.ui;

import com.emre.linewatch.collector.LineSample;

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
        return new HealthScore(0, "—", "Hat durumu bekleniyor",
                "Modemden ilk ölçüm bekleniyor.", "unknown");
    }

    public static HealthScore down(String reason) {
        return new HealthScore(0, "F", "Hat şu anda kopuk",
                reason == null || reason.isBlank() ? "Modem senkron kuramıyor." : reason, "down");
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
            case "A" -> "Hattınız çok iyi durumda";
            case "B" -> "Hattınız iyi durumda";
            case "C" -> "Hattınız orta seviyede";
            case "D" -> "Hattınız sorunlu";
            default -> "Hattınızda ciddi sorun var";
        };

        return new HealthScore(points, grade, headline,
                describe(drops24h, crcPerHour, snr, capacity), state);
    }

    private static String describe(int drops, double crcPerHour, double snr, double capacity) {
        List<String> issues = new ArrayList<>();
        if (drops > 0) issues.add("son 24 saatte " + drops + " kopma yaşandı");
        if (crcPerHour >= 10) issues.add("CRC sayacı saatte ~" + Fmt.count(Math.round(crcPerHour)) + " artıyor");
        if (snr < 9) issues.add("SNR marjı " + Fmt.dec1(snr) + " dB ile düşük");
        if (capacity < 70) issues.add("hattın kapasitesinin yalnızca " + Fmt.pct0(capacity) + "'i kullanılıyor");

        if (issues.isEmpty()) return "Son 24 saatte kopma yok, hata sayaçları sakin.";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < issues.size(); i++) {
            if (i == 0) sb.append(Character.toUpperCase(issues.get(i).charAt(0))).append(issues.get(i).substring(1));
            else if (i == issues.size() - 1) sb.append(" ve ").append(issues.get(i));
            else sb.append(", ").append(issues.get(i));
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
