package com.emre.linewatch.report;

import com.emre.linewatch.storage.LineEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Tek bir kesinti: hat kopmasi (DSL) ya da internet erisiminin kesilmesi (NET).
 *
 * <p>{@code approximate} kesintiler uygulamanin kapali oldugu sureleri kapsar:
 * motor yeniden acildiginda onceki durumu bilmedigi icin "geri geldi" olayi
 * yazilmaz, ayni kesinti icin her acilista yeni bir "koptu" olayi yazilir. Bu
 * kesintilerin suresi, baglantinin kopuk goruldugu ilk ve son olcum arasidir
 * (arada hic "bagli" olcum yok); bitis, sonraki ilk "bagli" olcumdur.
 *
 * <p>Bitis hic bilinmiyorsa {@code end} -1, sure de bilinmiyorsa {@code durationSec} -1.
 */
public record Outage(Kind kind, long start, long end, long durationSec, String detail,
                     boolean startedBefore, boolean approximate) {

    public enum Kind { DSL, NET }

    /** Ham olcumlerden baglanti durumunu sorgular (DSL icin modem, NET icin internet olcumleri). */
    public interface Timeline {
        /** {@code ts}'den sonraki ilk "bagli" olcumun zamani; yoksa -1. */
        long firstUpAfter(long ts);

        /** [from, to) araligindaki son "kopuk" olcumun zamani; yoksa -1. */
        long lastDownBetween(long from, long to);

        /**
         * Saklanan en eski olcumun zamani; hic yoksa Long.MAX_VALUE. Ham olcumler
         * belirli bir sure sonra silinir: bundan onceki donem icin "arada hic
         * bagli olcum yok" denemez, cunku veri yok.
         */
        long dataStart();

        Timeline NONE = new Timeline() {
            @Override
            public long firstUpAfter(long ts) { return -1; }

            @Override
            public long lastDownBetween(long from, long to) { return -1; }

            @Override
            public long dataStart() { return Long.MAX_VALUE; }
        };
    }

    public boolean endKnown() {
        return end >= 0;
    }

    public boolean durationKnown() {
        return durationSec >= 0;
    }

    /**
     * Olay listesinden (eskiden yeniye) kesintileri cikarir. Liste, donem
     * baslangicindan onceki son kopma/gelme olayini da icerebilir — donemden once
     * baslamis bir kesinti boylece kaybolmaz.
     *
     * <p>Kesinti suresi "geri geldi" olayinda saklaniyor. Donem disinda kalan
     * kisimlar kirpilir ({@code startedBefore} isaretlenir). {@code NET_DOWN} hat
     * zaten kopukken uretilmedigi icin bir {@code NET_UP} oncesinde acik kesinti
     * olmayabilir; o zaman baslangic kaydedilen sureden hesaplanir.
     */
    public static List<Outage> fromEvents(List<LineEvent> events, Kind kind, long from, long to, Timeline timeline) {
        LineEvent.Type downType = kind == Kind.DSL ? LineEvent.Type.LINK_DOWN : LineEvent.Type.NET_DOWN;
        LineEvent.Type upType = kind == Kind.DSL ? LineEvent.Type.LINK_UP : LineEvent.Type.NET_UP;

        List<Outage> out = new ArrayList<>();
        LineEvent open = null;
        for (LineEvent e : events) {
            if (e.type() == downType) {
                if (open != null) {
                    if (open.ts() < timeline.dataStart()) {
                        // Ham olcum yok: tekrar kaydi mi yeni kopma mi, dogrulanamaz
                        add(out, unknown(kind, open, from), to);
                    } else {
                        long up = timeline.firstUpAfter(open.ts());
                        // Arada hic "bagli" olcum yoksa bu ayni kesintinin tekrar kaydidir
                        // (uygulama yeniden baslatilmis): yeni kesinti sayilmaz.
                        if (up < 0 || up > e.ts()) continue;
                        add(out, closeFromTimeline(kind, open, up, from, to, timeline), to);
                    }
                }
                open = e;
            } else if (e.type() == upType) {
                long start;
                String detail;
                if (open != null) {
                    start = open.ts();
                    detail = open.detail();
                } else if (e.durationSec() > 0) {
                    start = e.ts() - e.durationSec() * 1000;
                    detail = "";
                } else {
                    continue;
                }
                open = null;
                // Donemden once bitmis kesinti (onceki son olay "geri geldi" ise)
                if (e.ts() < from) continue;
                long clipped = Math.max(start, from);
                add(out, new Outage(kind, clipped, e.ts(), Math.max(0, (e.ts() - clipped) / 1000),
                        detail == null ? "" : detail, start < from, false), to);
            }
        }
        if (open != null) {
            add(out, open.ts() < timeline.dataStart() ? unknown(kind, open, from)
                    : closeFromTimeline(kind, open, timeline.firstUpAfter(open.ts()), from, to, timeline), to);
        }
        return out;
    }

    /** Suresi ve bitisi bilinmeyen kesinti; donemden once basladiysa hic eklenmez (donemle ilgisi kanitlanamaz). */
    private static Outage unknown(Kind kind, LineEvent down, long from) {
        if (down.ts() < from) return null;
        return new Outage(kind, down.ts(), -1, -1, down.detail() == null ? "" : down.detail(), false, false);
    }

    /** Donemin tamamen disinda kalan (donem basindan once bitmis) kesintiler eklenmez. */
    private static void add(List<Outage> out, Outage o, long to) {
        if (o == null || o.start() > to) return;
        out.add(o);
    }

    /** "Geri geldi" olayi olmayan kesintiyi ham olcumlerle kapatir. */
    private static Outage closeFromTimeline(Kind kind, LineEvent down, long firstUp, long from, long to,
                                            Timeline timeline) {
        String detail = down.detail() == null ? "" : down.detail();
        long lastDown = timeline.lastDownBetween(down.ts(), firstUp < 0 ? Long.MAX_VALUE : firstUp);
        if (firstUp >= 0 && firstUp < from) return null;
        long start = Math.max(down.ts(), from);
        boolean before = down.ts() < from;
        long end = firstUp >= 0 && firstUp <= to ? firstUp : -1;
        if (lastDown <= down.ts()) {
            // Tek bir kopuk olcum: suresi bilinmiyor, en fazla bitisi (ilk bagli olcum) biliniyor
            return new Outage(kind, start, end, -1, detail, before, end >= 0);
        }
        long seenUntil = Math.min(lastDown, to);
        return new Outage(kind, start, end, Math.max(0, (seenUntil - start) / 1000), detail, before, true);
    }
}
