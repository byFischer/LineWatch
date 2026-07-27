package com.emre.linewatch.storage;

public record LineEvent(long id, long ts, Type type, String detail, long durationSec) {

    public enum Type {
        LINK_DOWN("Hat koptu"),
        LINK_UP("Hat geldi"),
        CRC_BURST("CRC hata patlaması"),
        LOW_SNR("Düşük SNR"),
        RETRAIN("Yeniden senkron"),
        NET_DOWN("İnternet yok (hat aktif)"),
        NET_UP("İnternet geldi"),
        REBOOT("Modem yeniden başlatıldı"),
        MODEM_UNREACHABLE("Modeme ulaşılamıyor"),
        COLLECTOR_ERROR("Toplayıcı hatası");

        public final String label;
        Type(String label) { this.label = label; }
    }
}
