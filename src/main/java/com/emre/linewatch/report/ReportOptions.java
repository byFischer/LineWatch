package com.emre.linewatch.report;

/**
 * Kullanicinin rapora ekledigi bilgiler.
 *
 * @param subscriber  abone / hizmet numarasi; bos olabilir
 * @param planMbps    abonelik (taahhut) indirme hizi; bilinmiyorsa 0
 * @param device      izlenen cihazin adi (orn. "Archer VR400"); modemsiz kurulumda bos
 * @param pollSeconds olcum araligi — veri kapsami hesabi icin
 * @param snrThresholdDb dusuk SNR uyari esigi; grafikte referans cizgisi olur
 */
public record ReportOptions(String subscriber, double planMbps, String device,
                            int pollSeconds, double snrThresholdDb) {

    public ReportOptions {
        subscriber = subscriber == null ? "" : subscriber.strip();
        device = device == null ? "" : device;
        planMbps = Math.max(0, planMbps);
        pollSeconds = Math.max(5, pollSeconds);
    }
}
