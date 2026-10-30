package com.emre.linewatch.report;

import com.emre.linewatch.collector.LineSample;
import com.emre.linewatch.collector.NetSample;
import com.emre.linewatch.storage.LineEvent;
import com.emre.linewatch.storage.NetSampleRepository;
import com.emre.linewatch.storage.SampleRepository.LineStats;

import java.util.List;
import java.util.Map;

/**
 * Raporun tum icerigi. {@link ReportBuilder} veritabanindan doldurur,
 * {@link HtmlReport} cizer; ikisi ayri oldugu icin hesaplar UI'siz test edilebilir.
 *
 * @param lineCoveragePct DSL olcumlerinin araligin ne kadarini kapladigi (%)
 * @param netCoveragePct  internet olcumlerinin araligin ne kadarini kapladigi (%)
 * @param crcTotal        araliktaki CRC artisi (sayac sifirlanmalari yok sayilir)
 * @param crcByHour       gunun saatine gore CRC artisi (0-23)
 * @param dslDropsByHour  gunun saatine gore hat kopmasi sayisi
 * @param netDropsByHour  gunun saatine gore internet kesintisi sayisi
 * @param hourlyUntil     bu andan onceki kisim ham olcumler yerine saatlik ozetlerden
 *                        geldi (ham veri silinmis); ozet kullanilmadiysa {@code from}
 */
public record ReportData(
        long from, long to, long generatedAt,
        ReportOptions options,
        LineStats line,
        NetSampleRepository.Summary net,
        double lineCoveragePct, double netCoveragePct,
        List<Outage> dslOutages, List<Outage> netOutages,
        long crcTotal,
        long[] crcByHour, int[] dslDropsByHour, int[] netDropsByHour,
        List<LineSample> lineSeries, List<NetSample> netSeries,
        Map<LineEvent.Type, Integer> eventCounts,
        long hourlyUntil) {

    /** DSL bolumleri yalnizca araliktaki DSL olcumu varsa cizilir. */
    public boolean hasLine() {
        return line.samples() > 0;
    }

    public boolean hasNet() {
        return net.hasData();
    }

    /** Raporun kapsadigi gercek sure: gelecege uzanan bitis "simdi"ye cekilir. */
    public long effectiveTo() {
        return Math.min(to, generatedAt);
    }

    /** Donemin bir kismi yalnizca saatlik ozetlerle temsil ediliyor mu. */
    public boolean usesHourly() {
        return hourlyUntil > from;
    }

    public long hours() {
        return Math.max(1, (effectiveTo() - from) / 3_600_000L);
    }
}
