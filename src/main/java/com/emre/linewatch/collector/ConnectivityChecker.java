package com.emre.linewatch.collector;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * "Hat Up ama internet var mi?" kontrolu: iki farkli saglayiciya TCP baglantisi
 * dener (Cloudflare 1.1.1.1:443, Google DNS 8.8.8.8:53). Ikisi de basarisizsa
 * internet yok kabul edilir.
 */
public final class ConnectivityChecker {

    private static final String[][] TARGETS = {{"1.1.1.1", "443"}, {"8.8.8.8", "53"}};

    private ConnectivityChecker() {}

    public static boolean isOnline() {
        for (String[] t : TARGETS) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(t[0], Integer.parseInt(t[1])), 2500);
                return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }
}
