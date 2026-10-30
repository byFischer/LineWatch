package com.emre.linewatch.storage;

import com.emre.linewatch.collector.LineSample;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * DSL olcumlerinin toplamlari: ortalama yerine toplam + adet tutulur, boylece
 * ham araliklar ve saatlik ozetler kesin olarak birlestirilebilir.
 *
 * <p>SNR ve hiz alanlari yalnizca hattin "Up" oldugu orneklemlerden gelir;
 * SNR asagi ayrica {@link SampleRepository#MIN_VALID_SNR} ustunde olmali.
 * Bir alanin adedi 0 ise toplami ve en kucugu anlamsizdir.
 */
public record LineAggregate(int samples, int upSamples,
                            long snrDownSum, int snrDownN, int snrDownMin,
                            long snrUpSum, int snrUpN,
                            long syncDownSum, int syncDownN, int syncDownMin,
                            long syncUpSum, int syncUpN,
                            long maxDownSum, int maxDownN,
                            long attenDownSum, int attenDownN,
                            String modulation) {

    public static final LineAggregate EMPTY =
            new LineAggregate(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "");

    private static final String UP = "LOWER(status) = 'up'";
    private static final String VALID_SNR = UP + " AND snr_down > " + SampleRepository.MIN_VALID_SNR;

    /**
     * samples tablosu uzerinde toplam ifadeleri (17 sutun, {@link #read} sirasiyla).
     * Hem rapor araliklarinda hem de saatlik ozet uretiminde ayni ifade kullanilir.
     */
    static final String COLUMNS = String.join(",\n",
            "COUNT(*)",
            "SUM(CASE WHEN " + UP + " THEN 1 ELSE 0 END)",
            "SUM(CASE WHEN " + VALID_SNR + " THEN snr_down END)",
            "COUNT(CASE WHEN " + VALID_SNR + " THEN snr_down END)",
            "MIN(CASE WHEN " + VALID_SNR + " THEN snr_down END)",
            "SUM(CASE WHEN " + UP + " THEN snr_up END)",
            "COUNT(CASE WHEN " + UP + " THEN snr_up END)",
            "SUM(CASE WHEN " + UP + " THEN sync_down END)",
            "COUNT(CASE WHEN " + UP + " THEN sync_down END)",
            "MIN(CASE WHEN " + UP + " THEN sync_down END)",
            "SUM(CASE WHEN " + UP + " THEN sync_up END)",
            "COUNT(CASE WHEN " + UP + " THEN sync_up END)",
            "SUM(CASE WHEN " + UP + " THEN max_down END)",
            "COUNT(CASE WHEN " + UP + " THEN max_down END)",
            "SUM(CASE WHEN " + UP + " THEN atten_down END)",
            "COUNT(CASE WHEN " + UP + " THEN atten_down END)",
            "MAX(modulation)");

    /** samples_hourly tablosunun ayni sirayla sutunlari. */
    static final String HOURLY_COLUMNS = """
            samples, up_samples, snr_down_sum, snr_down_n, snr_down_min, snr_up_sum, snr_up_n,
            sync_down_sum, sync_down_n, sync_down_min, sync_up_sum, sync_up_n,
            max_down_sum, max_down_n, atten_down_sum, atten_down_n, modulation""";

    /** {@code col}'dan baslayan 17 sutunu okur ({@link #COLUMNS} ya da {@link #HOURLY_COLUMNS}). */
    static LineAggregate read(ResultSet rs, int col) throws SQLException {
        int samples = rs.getInt(col);
        if (samples == 0) return EMPTY;
        String modulation = rs.getString(col + 16);
        return new LineAggregate(samples, rs.getInt(col + 1),
                rs.getLong(col + 2), rs.getInt(col + 3), rs.getInt(col + 4),
                rs.getLong(col + 5), rs.getInt(col + 6),
                rs.getLong(col + 7), rs.getInt(col + 8), rs.getInt(col + 9),
                rs.getLong(col + 10), rs.getInt(col + 11),
                rs.getLong(col + 12), rs.getInt(col + 13),
                rs.getLong(col + 14), rs.getInt(col + 15),
                modulation == null ? "" : modulation);
    }

    /** Iki araligin toplami (en kucukler ve modulasyon SQL'deki MIN / MAX gibi). */
    public LineAggregate plus(LineAggregate o) {
        if (o.samples == 0) return this;
        if (samples == 0) return o;
        return new LineAggregate(samples + o.samples, upSamples + o.upSamples,
                snrDownSum + o.snrDownSum, snrDownN + o.snrDownN, min(snrDownMin, snrDownN, o.snrDownMin, o.snrDownN),
                snrUpSum + o.snrUpSum, snrUpN + o.snrUpN,
                syncDownSum + o.syncDownSum, syncDownN + o.syncDownN,
                min(syncDownMin, syncDownN, o.syncDownMin, o.syncDownN),
                syncUpSum + o.syncUpSum, syncUpN + o.syncUpN,
                maxDownSum + o.maxDownSum, maxDownN + o.maxDownN,
                attenDownSum + o.attenDownSum, attenDownN + o.attenDownN,
                modulation.compareTo(o.modulation) >= 0 ? modulation : o.modulation);
    }

    private static int min(int a, int an, int b, int bn) {
        if (an == 0) return b;
        if (bn == 0) return a;
        return Math.min(a, b);
    }

    private static double avg(long sum, int n) {
        return n == 0 ? 0 : (double) sum / n;
    }

    /** Rapor ozeti; SNR/zayiflama dB'ye cevrilir. */
    public SampleRepository.LineStats toStats() {
        if (samples == 0) return SampleRepository.LineStats.EMPTY;
        return new SampleRepository.LineStats(samples, upSamples,
                avg(snrDownSum, snrDownN) / 10.0, (snrDownN == 0 ? 0 : snrDownMin) / 10.0,
                avg(snrUpSum, snrUpN) / 10.0,
                avg(syncDownSum, syncDownN), syncDownN == 0 ? 0 : syncDownMin,
                avg(syncUpSum, syncUpN), avg(maxDownSum, maxDownN),
                avg(attenDownSum, attenDownN) / 10.0, modulation);
    }

    /**
     * Grafik noktasi. Hat hic "Up" degilse hiz 0 olur (grafikte bosluk), gecerli SNR
     * yoksa SNR gecersiz isaret degerini alir; CRC alani verilen kumulatif degerdir.
     */
    public LineSample toPoint(long ts, long crcCumulative) {
        int invalid = SampleRepository.MIN_VALID_SNR - 1;
        return new LineSample(ts, upSamples > 0 ? "Up" : "Down", modulation,
                round(syncDownSum, syncDownN, 0), round(syncUpSum, syncUpN, 0),
                round(maxDownSum, maxDownN, 0), 0,
                round(snrDownSum, snrDownN, invalid), round(snrUpSum, snrUpN, invalid),
                round(attenDownSum, attenDownN, 0), 0, 0, 0,
                crcCumulative, 0, 0, 0, 0, 0, 0, 0);
    }

    private static int round(long sum, int n, int none) {
        return n == 0 ? none : (int) Math.round((double) sum / n);
    }
}
