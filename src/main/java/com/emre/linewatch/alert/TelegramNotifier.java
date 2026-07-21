package com.emre.linewatch.alert;

import com.emre.linewatch.config.Settings;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Telegram bot ile mesaj gonderir. Internet yokken (hat kopukken) mesajlar
 * kuyruga alinir; flush() baglanti dondugunde kuyrugu bosaltir.
 */
public class TelegramNotifier implements Notifier {

    private record Pending(long ts, String text) {}

    private final Settings settings;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final ConcurrentLinkedQueue<Pending> queue = new ConcurrentLinkedQueue<>();

    public TelegramNotifier(Settings settings) {
        this.settings = settings;
    }

    @Override
    public void notify(String title, String message, boolean urgent) {
        if (!settings.telegramEnabled() || settings.telegramToken().isBlank()) return;
        queue.add(new Pending(System.currentTimeMillis(),
                (urgent ? "⚠️ " : "ℹ️ ") + title + "\n" + message));
        flush();
    }

    /** Kuyruktaki mesajlari gondermeyi dener; basarisiz olursa kuyrukta kalirlar. */
    public void flush() {
        Pending p;
        while ((p = queue.peek()) != null) {
            if (!send(p)) return;
            queue.poll();
        }
    }

    public boolean hasPending() {
        return !queue.isEmpty();
    }

    /** Kuyruk/baslik formatlamasi olmadan dogrudan mesaj gonderir (bot cevaplari icin). */
    public boolean sendDirect(String text) {
        return send(new Pending(System.currentTimeMillis(), text));
    }

    private boolean send(Pending p) {
        try {
            String text = p.text();
            if (System.currentTimeMillis() - p.ts() > 60_000) {
                text += "\n(olay zamani: " + new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(p.ts())) + ")";
            }
            String body = "chat_id=" + URLEncoder.encode(settings.telegramChatId(), StandardCharsets.UTF_8)
                    + "&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create("https://api.telegram.org/bot" + settings.telegramToken() + "/sendMessage"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
