package com.emre.linewatch.collector;

/**
 * Internet erisim kalitesinin tek olcumu. DSL olcumunden bagimsizdir:
 * modem desteklenmiyor ya da hic yapilandirilmamis olsa da alinabilir.
 *
 * <p>Olculemeyen degerler -1 gelir ({@link #hasLatency()} / {@link #hasJitter()}
 * ile kontrol edilir), 0 ile karistirilmamalidir.
 */
public record NetSample(
        long ts,
        boolean online,
        double latencyMs,   // basarili yoklamalarin ortalamasi; olculemedi = -1
        double jitterMs,    // ardisik yoklamalar arasi ortalama sapma; olculemedi = -1
        int lossPct,        // basarisiz yoklama yuzdesi (0-100)
        int sent,
        int received,
        String target       // olcumun alindigi hedef; hicbirine ulasilamadiysa ""
) {

    public boolean hasLatency() {
        return latencyMs >= 0;
    }

    public boolean hasJitter() {
        return jitterMs >= 0;
    }

    /** Hicbir hedefe ulasilamadigi durum. */
    public static NetSample offline(long ts, int sent) {
        return new NetSample(ts, false, -1, -1, 100, sent, 0, "");
    }
}
