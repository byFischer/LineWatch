package com.emre.linewatch.collector;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

/**
 * Internet erisim kalitesini olcer: gecikme, jitter ve paket kaybi.
 *
 * <p>ICMP ping kullanilmiyor: Java'da ham soket acmak yonetici hakki ister,
 * {@code InetAddress.isReachable} ise Windows'ta cogu zaman sessizce TCP'ye
 * duserek yaniltici sonuc verir. Bunun yerine hedefin TCP portuna baglanma
 * suresi olculuyor — SYN/SYN-ACK gidis donusu oldugu icin bir RTT'ye denk gelir.
 *
 * <p>Yoklamalar once birincil hedefe yapilir. O hedef tamamen sessiz kalirsa
 * (port engelli olabilir) yedek hedefler tek yoklamayla denenir; boylece tek bir
 * saglayicinin erisilemez olmasi "internet yok" olarak raporlanmaz.
 */
public class NetProbe {

    /** Yoklama hedefi. Hepsi TLS/DNS portu dinleyen, yuksek erisilebilirlikli sunucular. */
    public record Target(String host, int port, String label) {
    }

    public static final List<Target> DEFAULT_TARGETS = List.of(
            new Target("1.1.1.1", 443, "Cloudflare"),
            new Target("8.8.8.8", 443, "Google"),
            new Target("9.9.9.9", 443, "Quad9"));

    private static final int PROBES = 3;
    private static final int TIMEOUT_MS = 1500;
    /** Yoklamalar arasi bekleme: ard arda acilan soketler ayni ani olcup jitter'i yapay olarak sifirlamasin. */
    private static final int GAP_MS = 60;

    private final List<Target> targets;
    private final int probes;
    private final int timeoutMs;

    public NetProbe() {
        this(DEFAULT_TARGETS, PROBES, TIMEOUT_MS);
    }

    /** Test icin: hedefler ve sureler disaridan verilebilir. */
    public NetProbe(List<Target> targets, int probes, int timeoutMs) {
        if (targets.isEmpty()) throw new IllegalArgumentException("En az bir hedef gerekli");
        this.targets = List.copyOf(targets);
        this.probes = Math.max(1, probes);
        this.timeoutMs = timeoutMs;
    }

    /**
     * Tek bir olcum turu yapar. Bloklar; cagiran taraf arka plan is parcaciginda
     * calistirmalidir. En kotu durumda
     * {@code probes * timeout + (hedefSayisi - 1) * timeout} kadar surer.
     */
    public NetSample measure() {
        long ts = System.currentTimeMillis();
        int totalSent = 0;

        Target primary = targets.get(0);
        double[] rtt = new double[probes];
        int ok = 0;
        for (int i = 0; i < probes; i++) {
            if (i > 0) pause();
            totalSent++;
            double ms = connectMs(primary);
            if (ms >= 0) rtt[ok++] = ms;
        }
        if (ok > 0) {
            return new NetSample(ts, true, mean(rtt, ok), jitter(rtt, ok),
                    lossPct(probes - ok, probes), totalSent, ok, primary.label());
        }

        // Birincil hedef sessiz: internet gercekten yok mu, yoksa sadece o hedef mi
        // erisilemez? Yedeklere tek yoklama yeter.
        for (int i = 1; i < targets.size(); i++) {
            Target backup = targets.get(i);
            totalSent++;
            double ms = connectMs(backup);
            if (ms >= 0) {
                // Birincil hedefin kayiplari gercek kayip degil (o hedefe ozel bir sorun),
                // bu yuzden kayip orani yalnizca basarili yedek uzerinden raporlanir.
                return new NetSample(ts, true, ms, -1, 0, totalSent, 1, backup.label());
            }
        }
        return NetSample.offline(ts, totalSent);
    }

    /** Baglanti suresini ms cinsinden dondurur; ulasilamadiysa -1. */
    private double connectMs(Target t) {
        long t0 = System.nanoTime();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(t.host(), t.port()), timeoutMs);
            return (System.nanoTime() - t0) / 1_000_000.0;
        } catch (Exception e) {
            return -1;
        }
    }

    private void pause() {
        try {
            Thread.sleep(GAP_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- hesaplamalar (paket-ozel: test edilebilir olsun diye) ----

    static double mean(double[] v, int n) {
        if (n <= 0) return -1;
        double sum = 0;
        for (int i = 0; i < n; i++) sum += v[i];
        return sum / n;
    }

    /**
     * Ardisik olcumler arasindaki mutlak farkin ortalamasi (RFC 3550'nin
     * sadelestirilmis hali). Tek olcumden jitter cikarilamaz -> -1.
     */
    static double jitter(double[] v, int n) {
        if (n < 2) return -1;
        double sum = 0;
        for (int i = 1; i < n; i++) sum += Math.abs(v[i] - v[i - 1]);
        return sum / (n - 1);
    }

    static int lossPct(int lost, int sent) {
        return sent <= 0 ? 0 : (int) Math.round(100.0 * lost / sent);
    }
}
