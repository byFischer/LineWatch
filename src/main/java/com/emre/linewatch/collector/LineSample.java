package com.emre.linewatch.collector;

/**
 * Modemden alinan tek bir DSL olcumu.
 * SNR/attenuation/power degerleri modemden x10 tam sayi gelir (117 = 11.7 dB).
 * Hizlar kbps, CRC/FEC/HEC sayaclari kumulatiftir.
 */
public record LineSample(
        long ts,
        String status,          // "Up" / "Down" / vs.
        String modulation,      // VDSL2, ADSL2+ ...
        int syncDown, int syncUp,           // kbps
        int maxRateDown, int maxRateUp,     // kbps
        int snrDown, int snrUp,             // dB x10
        int attenDown, int attenUp,         // dB x10
        int powerDown, int powerUp,         // dBm x10
        long crcDown, long crcUp,           // kumulatif
        long fecDown, long fecUp,
        long hecDown, long hecUp,
        long erroredSecs, long severelyErroredSecs
) {
    public boolean isUp() {
        return "Up".equalsIgnoreCase(status);
    }
}
