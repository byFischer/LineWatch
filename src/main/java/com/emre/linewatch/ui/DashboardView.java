package com.emre.linewatch.ui;

import com.emre.linewatch.collector.LineSample;
import com.emre.linewatch.storage.EventRepository;
import com.emre.linewatch.storage.LineEvent;
import com.emre.linewatch.storage.SampleRepository;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Pano: hero durum bandi + stat kartlari + gunluk ozet. */
public class DashboardView extends VBox {

    private final Label heroStatus = new Label("BEKLENIYOR");
    private final Label heroDetail = new Label("Modemden ilk veri bekleniyor...");
    private final Label heroUpdated = new Label("");

    private final StatCard sync = new StatCard("SENKRON HIZI", "down / up");
    private final StatCard snr = new StatCard("SNR MARGIN", "down / up");
    private final StatCard atten = new StatCard("ATTENUATION", "down / up");
    private final StatCard crc = new StatCard("CRC HATALARI", "kumulatif down / up");
    private final StatCard capacity = new StatCard("HAT KAPASITESI", "kullanim orani");
    private final StatCard power = new StatCard("CIKIS GUCU", "down / up");

    private final StatCard todayCrc = new StatCard("BUGUNKU CRC ARTISI", "gece yarisindan beri");
    private final StatCard todayDrops = new StatCard("BUGUNKU KOPMA", "LINK_DOWN sayisi");
    private final StatCard lastEvent = new StatCard("SON OLAY", "");

    private final HBox uptimeStrip = new HBox(2);
    private final Label uptimeInfo = new Label();
    private static final int STRIP_BLOCKS = 96; // 24 saat / 15 dk

    private final SampleRepository samples;
    private final EventRepository events;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dashboard-stats");
        t.setDaemon(true);
        return t;
    });

    private LineSample previous;
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss");

    public DashboardView(SampleRepository samples, EventRepository events) {
        this.samples = samples;
        this.events = events;
        setPadding(new Insets(16));
        setSpacing(14);

        // hero
        heroStatus.getStyleClass().addAll("hero-status");
        heroDetail.getStyleClass().add("hero-sub");
        heroUpdated.getStyleClass().add("hero-sub");
        VBox heroLeft = new VBox(4, heroStatus, heroDetail);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        VBox heroRight = new VBox(heroUpdated);
        heroRight.setAlignment(Pos.BOTTOM_RIGHT);
        HBox hero = new HBox(heroLeft, spacer, heroRight);
        hero.getStyleClass().add("hero");
        hero.setAlignment(Pos.CENTER_LEFT);

        Label liveTitle = new Label("ANLIK DEGERLER");
        liveTitle.getStyleClass().add("section-title");
        GridPane liveGrid = grid(sync, snr, atten, crc, capacity, power);

        Label todayTitle = new Label("BUGUN");
        todayTitle.getStyleClass().add("section-title");
        GridPane todayGrid = grid(todayCrc, todayDrops, lastEvent);

        // uptime seridi: son 24 saat, 15 dk'lik bloklar
        Label stripTitle = new Label("SON 24 SAAT");
        stripTitle.getStyleClass().add("section-title");
        for (int i = 0; i < STRIP_BLOCKS; i++) {
            Region block = new Region();
            block.setPrefHeight(22);
            HBox.setHgrow(block, Priority.ALWAYS);
            block.setMaxWidth(Double.MAX_VALUE);
            block.setStyle("-fx-background-color:#1a2333; -fx-background-radius:2;");
            uptimeStrip.getChildren().add(block);
        }
        uptimeInfo.getStyleClass().add("card-sub");
        VBox stripCard = new VBox(8, stripTitle, uptimeStrip, uptimeInfo);
        stripCard.getStyleClass().add("card");

        getChildren().addAll(hero, stripCard, liveTitle, liveGrid, todayTitle, todayGrid);
    }

    private static GridPane grid(StatCard... cards) {
        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(12);
        for (int i = 0; i < 3; i++) {
            ColumnConstraints c = new ColumnConstraints();
            c.setPercentWidth(100.0 / 3);
            g.getColumnConstraints().add(c);
        }
        for (int i = 0; i < cards.length; i++) {
            GridPane.setHgrow(cards[i], Priority.ALWAYS);
            cards[i].setMaxWidth(Double.MAX_VALUE);
            g.add(cards[i], i % 3, i / 3);
        }
        return g;
    }

    /** Herhangi bir thread'den cagrilabilir. */
    public void update(LineSample s) {
        LineSample prev = previous;
        previous = s;
        Platform.runLater(() -> {
            boolean up = s.isUp();
            heroStatus.setText(up ? "● HAT AKTIF" : "● HAT KOPUK");
            heroStatus.getStyleClass().removeAll("hero-status-up", "hero-status-down");
            heroStatus.getStyleClass().add(up ? "hero-status-up" : "hero-status-down");
            heroDetail.setText(s.modulation() + "  —  " + s.status());
            heroUpdated.setText("son guncelleme " + timeFmt.format(new Date(s.ts())));

            sync.set(String.format("%.1f / %.1f", s.syncDown() / 1000.0, s.syncUp() / 1000.0), "Mbps  (down / up)", null);
            double snrDb = s.snrDown() / 10.0;
            snr.set(String.format("%.1f / %.1f", snrDb, s.snrUp() / 10.0), "dB  (down / up)",
                    snrDb < 6 ? "card-value-bad" : snrDb < 9 ? "card-value-warn" : "card-value-good");
            atten.set(String.format("%.1f / %.1f", s.attenDown() / 10.0, s.attenUp() / 10.0), "dB  (down / up)", null);

            String delta = "";
            if (prev != null && s.crcDown() >= prev.crcDown() && s.crcDown() > prev.crcDown()) {
                delta = "   +" + (s.crcDown() - prev.crcDown()) + " yeni";
            }
            crc.set(String.format("%,d / %,d", s.crcDown(), s.crcUp()), "kumulatif" + delta,
                    delta.isEmpty() ? null : "card-value-warn");

            double ratio = s.maxRateDown() > 0 ? 100.0 * s.syncDown() / s.maxRateDown() : 0;
            capacity.set(String.format("%%%.0f", ratio),
                    String.format("%.1f / %.1f Mbps kapasite", s.maxRateDown() / 1000.0, s.maxRateUp() / 1000.0),
                    ratio < 70 ? "card-value-warn" : null);
            power.set(String.format("%.1f / %.1f", s.powerDown() / 10.0, s.powerUp() / 10.0), "dBm  (down / up)", null);
        });
        refreshDaily(s);
    }

    private void refreshDaily(LineSample s) {
        worker.execute(() -> {
            refreshUptimeStrip();
            try {
                long midnight = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
                long firstCrc = samples.firstCrcSince(midnight);
                long crcToday = firstCrc >= 0 && s.crcDown() >= firstCrc ? s.crcDown() - firstCrc : 0;
                int drops = events.countSince(midnight, LineEvent.Type.LINK_DOWN);
                List<LineEvent> recent = events.findRecent(1);
                Platform.runLater(() -> {
                    todayCrc.set(String.format("%,d", crcToday), "gece yarisindan beri",
                            crcToday > 1000 ? "card-value-bad" : crcToday > 100 ? "card-value-warn" : null);
                    todayDrops.set(String.valueOf(drops), "kopma sayisi",
                            drops > 0 ? "card-value-bad" : "card-value-good");
                    if (!recent.isEmpty()) {
                        LineEvent e = recent.get(0);
                        lastEvent.set(e.type().label,
                                new SimpleDateFormat("dd.MM HH:mm").format(new Date(e.ts())), null);
                    } else {
                        lastEvent.set("—", "henuz olay yok", null);
                    }
                });
            } catch (Exception ignored) {
            }
        });
    }

    /** Son 24 saati 15 dk'lik bloklara boler: yesil=up, kirmizi=sorun, gri=veri yok. */
    private void refreshUptimeStrip() {
        try {
            long now = System.currentTimeMillis();
            long from = now - 24L * 3600_000;
            long blockMs = 24L * 3600_000 / STRIP_BLOCKS;
            List<long[]> points = samples.findStatusSince(from);
            int[] state = new int[STRIP_BLOCKS]; // 0=veri yok, 1=up, 2=sorun
            for (long[] p : points) {
                int idx = (int) ((p[0] - from) / blockMs);
                if (idx < 0 || idx >= STRIP_BLOCKS) continue;
                if (p[1] == 0) state[idx] = 2;
                else if (state[idx] == 0) state[idx] = 1;
            }
            long downBlocks = java.util.Arrays.stream(state).filter(v -> v == 2).count();
            long knownBlocks = java.util.Arrays.stream(state).filter(v -> v != 0).count();
            double pct = knownBlocks > 0 ? 100.0 * (knownBlocks - downBlocks) / knownBlocks : 0;
            Platform.runLater(() -> {
                SimpleDateFormat hf = new SimpleDateFormat("HH:mm");
                for (int i = 0; i < STRIP_BLOCKS; i++) {
                    Region b = (Region) uptimeStrip.getChildren().get(i);
                    String color = switch (state[i]) {
                        case 1 -> "#2ea04f";
                        case 2 -> "#e5484d";
                        default -> "#1a2333";
                    };
                    b.setStyle("-fx-background-color:" + color + "; -fx-background-radius:2;");
                    long bs = from + i * blockMs;
                    javafx.scene.control.Tooltip.install(b, new javafx.scene.control.Tooltip(
                            hf.format(new Date(bs)) + " - " + hf.format(new Date(bs + blockMs)) + "  " +
                                    switch (state[i]) {
                                        case 1 -> "sorunsuz";
                                        case 2 -> "kopma/sorun";
                                        default -> "veri yok";
                                    }));
                }
                uptimeInfo.setText(String.format("uptime %%%.1f  —  %d blokta sorun", pct, downBlocks));
            });
        } catch (Exception ignored) {
        }
    }

    public void showError(String message) {
        Platform.runLater(() -> {
            heroStatus.setText("● BAGLANTI SORUNU");
            heroStatus.getStyleClass().removeAll("hero-status-up", "hero-status-down");
            heroStatus.getStyleClass().add("hero-status-down");
            heroDetail.setText(message == null ? "" : message);
        });
    }

    /** Baslik + buyuk deger + alt aciklamadan olusan stat kutusu. */
    private static class StatCard extends VBox {
        private final Label value = new Label("--");
        private final Label sub = new Label();

        StatCard(String title, String subtitle) {
            getStyleClass().add("card");
            setSpacing(4);
            Label t = new Label(title);
            t.getStyleClass().add("card-title");
            value.getStyleClass().add("card-value");
            sub.getStyleClass().add("card-sub");
            sub.setText(subtitle);
            getChildren().addAll(t, value, sub);
        }

        void set(String v, String s, String valueStyle) {
            value.setText(v);
            sub.setText(s);
            value.getStyleClass().removeAll("card-value-warn", "card-value-bad", "card-value-good");
            if (valueStyle != null) value.getStyleClass().add(valueStyle);
        }
    }
}
