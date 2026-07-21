package com.emre.linewatch.storage;

public record LineEvent(long id, long ts, Type type, String detail, long durationSec) {

    public enum Type {
        LINK_DOWN("Hat koptu"),
        LINK_UP("Hat geldi"),
        CRC_BURST("CRC hata patlamasi"),
        LOW_SNR("Dusuk SNR"),
        RETRAIN("Yeniden senkron"),
        NET_DOWN("Internet yok (hat Up)"),
        NET_UP("Internet geldi"),
        REBOOT("Modem yeniden baslatildi"),
        MODEM_UNREACHABLE("Modeme ulasilamiyor"),
        COLLECTOR_ERROR("Toplayici hatasi");

        public final String label;
        Type(String label) { this.label = label; }
    }
}
