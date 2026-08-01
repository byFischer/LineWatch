package com.emre.linewatch.collector;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gercek aga cikmadan test edilir: hedef olarak localhost'ta acilan bir
 * ServerSocket kullanilir, ulasilamayan hedef icin de kapali bir port.
 */
class NetProbeTest {

    // ---- hesaplamalar ----

    @Test
    void meanIsAverageOfSamples() {
        assertEquals(22.333, NetProbe.mean(new double[]{20, 25, 22}, 3), 0.01);
    }

    @Test
    void jitterIsMeanAbsoluteDifference() {
        // |25-20| = 5, |22-25| = 3 -> ortalama 4
        assertEquals(4.0, NetProbe.jitter(new double[]{20, 25, 22}, 3), 0.001);
    }

    @Test
    void jitterNeedsAtLeastTwoSamples() {
        assertEquals(-1, NetProbe.jitter(new double[]{10}, 1));
        assertEquals(-1, NetProbe.jitter(new double[]{}, 0));
    }

    @Test
    void lossPercentIsRounded() {
        assertEquals(0, NetProbe.lossPct(0, 3));
        assertEquals(33, NetProbe.lossPct(1, 3));
        assertEquals(100, NetProbe.lossPct(3, 3));
    }

    // ---- olcum ----

    @Test
    void reachableTargetProducesOnlineSample() throws IOException {
        try (ServerSocket server = new ServerSocket(0)) {
            NetProbe probe = new NetProbe(
                    List.of(new NetProbe.Target("127.0.0.1", server.getLocalPort(), "yerel")), 3, 500);

            NetSample s = probe.measure();

            assertTrue(s.online());
            assertTrue(s.hasLatency(), "gecikme olculmeliydi");
            assertTrue(s.hasJitter(), "3 yoklamadan jitter cikmali");
            assertEquals(0, s.lossPct());
            assertEquals(3, s.sent());
            assertEquals(3, s.received());
            assertEquals("yerel", s.target());
        }
    }

    @Test
    void unreachableTargetProducesOfflineSample() throws IOException {
        NetProbe probe = new NetProbe(
                List.of(new NetProbe.Target("127.0.0.1", closedPort(), "yok")), 2, 300);

        NetSample s = probe.measure();

        assertFalse(s.online());
        assertFalse(s.hasLatency());
        assertFalse(s.hasJitter());
        assertEquals(100, s.lossPct());
        assertEquals(0, s.received());
        assertEquals("", s.target());
    }

    @Test
    void fallsBackToBackupWhenPrimaryIsSilent() throws IOException {
        // Birincil hedefin erisilemez olmasi "internet yok" demek degil;
        // yedek yanit veriyorsa olcum cevrimici sayilmali.
        try (ServerSocket backup = new ServerSocket(0)) {
            NetProbe probe = new NetProbe(List.of(
                    new NetProbe.Target("127.0.0.1", closedPort(), "olu"),
                    new NetProbe.Target("127.0.0.1", backup.getLocalPort(), "yedek")), 2, 300);

            NetSample s = probe.measure();

            assertTrue(s.online());
            assertEquals("yedek", s.target());
            // Yedege tek yoklama gider: jitter cikmaz, kayip birincil hedefe yazilmaz
            assertFalse(s.hasJitter());
            assertEquals(0, s.lossPct());
            assertEquals(1, s.received());
        }
    }

    @Test
    void rejectsEmptyTargetList() {
        assertThrows(IllegalArgumentException.class, () -> new NetProbe(List.of(), 3, 300));
    }

    /** Acilip hemen kapatilan bir port: baglanti denemesi reddedilir. */
    private static int closedPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
