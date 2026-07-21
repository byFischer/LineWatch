package com.emre.linewatch.alert;

import com.emre.linewatch.MonitorService;
import com.emre.linewatch.collector.LineSample;
import com.emre.linewatch.config.Settings;
import com.emre.linewatch.storage.EventRepository;
import com.emre.linewatch.storage.LineEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Telegram getUpdates long-polling: sadece ayarlardaki chat_id'den gelen
 * komutlara cevap verir. Komutlar: /durum, /olaylar, /yardim
 */
public class TelegramBot {

    private final Settings settings;
    private final TelegramNotifier notifier;
    private final MonitorService monitor;
    private final EventRepository events;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private volatile boolean running;
    private long offset = 0;

    private static final Pattern UPDATE = Pattern.compile(
            "\"update_id\":(\\d+).*?\"chat\":\\{\"id\":(-?\\d+).*?\"text\":\"((?:[^\"\\\\]|\\\\.)*)\"",
            Pattern.DOTALL);

    public TelegramBot(Settings settings, TelegramNotifier notifier,
                       MonitorService monitor, EventRepository events) {
        this.settings = settings;
        this.notifier = notifier;
        this.monitor = monitor;
        this.events = events;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(this::loop, "telegram-bot");
        t.setDaemon(true);
        t.start();
    }

    private void loop() {
        while (running) {
            try {
                if (!settings.telegramEnabled() || settings.telegramToken().isBlank()) {
                    Thread.sleep(10_000);
                    continue;
                }
                String url = "https://api.telegram.org/bot" + settings.telegramToken()
                        + "/getUpdates?timeout=25&offset=" + offset;
                HttpResponse<String> r = http.send(
                        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(35)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) {
                    Thread.sleep(15_000);
                    continue;
                }
                Matcher m = UPDATE.matcher(r.body());
                while (m.find()) {
                    offset = Long.parseLong(m.group(1)) + 1;
                    String chatId = m.group(2);
                    String text = m.group(3).replace("\\/", "/").trim().toLowerCase();
                    if (chatId.equals(settings.telegramChatId())) handle(text);
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                try { Thread.sleep(15_000); } catch (InterruptedException ie) { return; }
            }
        }
    }

    private void handle(String text) {
        if (text.startsWith("/durum")) {
            notifier.sendDirect(statusText());
        } else if (text.startsWith("/olaylar")) {
            notifier.sendDirect(eventsText());
        } else if (text.startsWith("/yardim") || text.startsWith("/help") || text.startsWith("/start")) {
            notifier.sendDirect("LineWatch komutlari:\n/durum — anlik hat durumu\n/olaylar — son 5 olay\n/yardim — bu mesaj");
        }
    }

    private String statusText() {
        LineSample s = monitor.lastSample();
        if (s == null) return "Henuz veri toplanmadi.";
        return String.format(
                "%s LineWatch durum (%s)%n"
                        + "Hat: %s (%s)%n"
                        + "Sync: %.1f / %.1f Mbps%n"
                        + "SNR: %.1f / %.1f dB%n"
                        + "CRC: %,d (down) / %,d (up)",
                s.isUp() ? "✅" : "❌",
                new SimpleDateFormat("HH:mm:ss").format(new Date(s.ts())),
                s.status(), s.modulation(),
                s.syncDown() / 1000.0, s.syncUp() / 1000.0,
                s.snrDown() / 10.0, s.snrUp() / 10.0,
                s.crcDown(), s.crcUp());
    }

    private String eventsText() {
        try {
            List<LineEvent> list = events.findRecent(5);
            if (list.isEmpty()) return "Henuz olay yok.";
            StringBuilder sb = new StringBuilder("Son olaylar:\n");
            SimpleDateFormat fmt = new SimpleDateFormat("dd.MM HH:mm");
            for (LineEvent e : list) {
                sb.append(fmt.format(new Date(e.ts()))).append(" — ").append(e.type().label);
                if (e.detail() != null && !e.detail().isBlank()) {
                    String d = e.detail();
                    sb.append(": ").append(d.length() > 80 ? d.substring(0, 80) + "..." : d);
                }
                sb.append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return "Olaylar okunamadi: " + e.getMessage();
        }
    }

    public void stop() {
        running = false;
    }
}
