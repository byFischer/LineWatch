package com.emre.linewatch.storage;

import com.emre.linewatch.config.I18n;

public record LineEvent(long id, long ts, Type type, String detail, long durationSec) {

    /**
     * Olay tipleri. Etiket sabit degil, {@link #label()} ile secili dilden okunur —
     * tipin adi ({@code name()}) veritabaninda saklandigi icin dil degisse de
     * eski kayitlar okunmaya devam eder.
     */
    public enum Type {
        LINK_DOWN("event.linkDown"),
        LINK_UP("event.linkUp"),
        CRC_BURST("event.crcBurst"),
        LOW_SNR("event.lowSnr"),
        RETRAIN("event.retrain"),
        NET_DOWN("event.netDown"),
        NET_UP("event.netUp"),
        HIGH_LATENCY("event.highLatency"),
        PACKET_LOSS("event.packetLoss"),
        REBOOT("event.reboot"),
        MODEM_UNREACHABLE("event.modemUnreachable"),
        COLLECTOR_ERROR("event.collectorError");

        private final String key;

        Type(String key) {
            this.key = key;
        }

        public String label() {
            return I18n.t(key);
        }
    }
}
