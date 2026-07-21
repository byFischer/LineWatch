package com.emre.linewatch.collector;

/**
 * Collector'i UI olmadan test etmek icin:
 *   mvn -q compile exec:java ... ya da dogrudan:
 *   java -cp ... com.emre.linewatch.collector.CollectorMain <sifre> [host] [adet]
 */
public class CollectorMain {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Kullanim: CollectorMain <sifre> [host] [adet]");
            System.exit(1);
        }
        String host = args.length > 1 ? args[1] : "http://192.168.1.1";
        int count = args.length > 2 ? Integer.parseInt(args[2]) : 1;

        TpLinkCollector c = new TpLinkCollector(host, args[0]);
        try {
            for (int i = 0; i < count; i++) {
                if (i > 0) Thread.sleep(5000);
                LineSample s = c.collect();
                System.out.printf("[%tT] %s %s | sync %d/%d kbps | snr %.1f/%.1f dB | atten %.1f/%.1f dB | crc %d/%d%n",
                        s.ts(), s.status(), s.modulation(),
                        s.syncDown(), s.syncUp(),
                        s.snrDown() / 10.0, s.snrUp() / 10.0,
                        s.attenDown() / 10.0, s.attenUp() / 10.0,
                        s.crcDown(), s.crcUp());
            }
        } finally {
            c.close();
        }
    }
}
