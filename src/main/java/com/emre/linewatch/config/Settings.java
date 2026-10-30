package com.emre.linewatch.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** %APPDATA%/LineWatch/config.properties dosyasinda tutulan ayarlar. */
public class Settings {

    public static final Path DIR = Path.of(System.getenv("APPDATA"), "LineWatch");
    private static final Path FILE = DIR.resolve("config.properties");

    private final Properties p = new Properties();

    public static Settings load() {
        Settings s = new Settings();
        if (Files.exists(FILE)) {
            try (InputStream in = Files.newInputStream(FILE)) {
                s.p.load(in);
            } catch (IOException ignored) {
            }
        }
        return s;
    }

    public void save() throws IOException {
        Files.createDirectories(DIR);
        try (OutputStream out = Files.newOutputStream(FILE)) {
            p.store(out, "LineWatch ayarlari");
        }
    }

    public String modemHost() { return p.getProperty("modem.host", "http://192.168.1.1"); }
    public void modemHost(String v) { p.setProperty("modem.host", v); }

    public String modemPassword() { return p.getProperty("modem.password", ""); }
    public void modemPassword(String v) { p.setProperty("modem.password", v); }

    public int pollSeconds() { return getInt("poll.seconds", 15); }
    public void pollSeconds(int v) { p.setProperty("poll.seconds", String.valueOf(v)); }

    public long crcDeltaThreshold() { return getInt("alert.crcDelta", 50); }
    public void crcDeltaThreshold(long v) { p.setProperty("alert.crcDelta", String.valueOf(v)); }

    /** dB cinsinden (ondalik olabilir, orn. 6.0) */
    public double snrThresholdDb() {
        try { return Double.parseDouble(p.getProperty("alert.snrDb", "6.0")); }
        catch (NumberFormatException e) { return 6.0; }
    }
    public void snrThresholdDb(double v) { p.setProperty("alert.snrDb", String.valueOf(v)); }

    public int alertCooldownMinutes() { return getInt("alert.cooldownMin", 15); }
    public void alertCooldownMinutes(int v) { p.setProperty("alert.cooldownMin", String.valueOf(v)); }

    // ---- internet erisim olcumu (modem desteklenmese de calisir) ----

    /** Modem izleme acik mi; varsayilan olarak sifre girilmisse acik. */
    public boolean modemEnabled() { return getBool("monitor.modem", !modemPassword().isBlank()); }
    public void modemEnabled(boolean v) { p.setProperty("monitor.modem", String.valueOf(v)); }

    public boolean netProbeEnabled() { return getBool("monitor.net", true); }
    public void netProbeEnabled(boolean v) { p.setProperty("monitor.net", String.valueOf(v)); }

    /** Bu degerin ustundeki ortalama gecikme uyari uretir (ms). */
    public int latencyThresholdMs() { return getInt("alert.latencyMs", 150); }
    public void latencyThresholdMs(int v) { p.setProperty("alert.latencyMs", String.valueOf(v)); }

    /** Bu oranin ustundeki paket kaybi uyari uretir (%). */
    public int lossThresholdPct() { return getInt("alert.lossPct", 5); }
    public void lossThresholdPct(int v) { p.setProperty("alert.lossPct", String.valueOf(v)); }

    public boolean toastEnabled() { return getBool("notify.toast", true); }
    public void toastEnabled(boolean v) { p.setProperty("notify.toast", String.valueOf(v)); }

    public boolean telegramEnabled() { return getBool("notify.telegram", false); }
    public void telegramEnabled(boolean v) { p.setProperty("notify.telegram", String.valueOf(v)); }

    public String telegramToken() { return p.getProperty("telegram.token", ""); }
    public void telegramToken(String v) { p.setProperty("telegram.token", v); }

    public String telegramChatId() { return p.getProperty("telegram.chatId", ""); }
    public void telegramChatId(String v) { p.setProperty("telegram.chatId", v); }

    /** Ham olcumlerin (15 sn'lik kayitlar) saklanma suresi, gun. */
    public int retentionDays() { return getInt("db.retentionDays", 30); }
    public void retentionDays(int v) { p.setProperty("db.retentionDays", String.valueOf(Math.max(1, v))); }

    /** Saatlik ozetlerin saklanma suresi, gun; ham olcumler silindikten sonra rapor bunlara dayanir. */
    public int hourlyRetentionDays() { return getInt("db.hourlyRetentionDays", 365); }
    public void hourlyRetentionDays(int v) { p.setProperty("db.hourlyRetentionDays", String.valueOf(Math.max(1, v))); }

    // ---- kanit raporu: bir sonraki raporda hazir gelsin diye saklanir ----

    public String reportSubscriber() { return p.getProperty("report.subscriber", ""); }
    public void reportSubscriber(String v) { p.setProperty("report.subscriber", v); }

    /** Abonelik indirme hizi (Mbps); bilinmiyorsa 0. */
    public double reportPlanMbps() {
        try { return Math.max(0, Double.parseDouble(p.getProperty("report.planMbps", "0"))); }
        catch (NumberFormatException e) { return 0; }
    }
    public void reportPlanMbps(double v) { p.setProperty("report.planMbps", String.valueOf(v)); }

    /** Tema tercihi: AUTO / DARK / LIGHT (bkz. ui.Theme.Mode). */
    public String theme() { return p.getProperty("ui.theme", "AUTO"); }
    public void theme(String v) { p.setProperty("ui.theme", v); }

    /** Arayuz dili: "auto" (sistem dili), "tr" ya da "en" (bkz. I18n). */
    public String language() { return p.getProperty("ui.language", I18n.AUTO); }
    public void language(String v) { p.setProperty("ui.language", v); }

    private int getInt(String k, int def) {
        try { return Integer.parseInt(p.getProperty(k, String.valueOf(def))); }
        catch (NumberFormatException e) { return def; }
    }

    private boolean getBool(String k, boolean def) {
        return Boolean.parseBoolean(p.getProperty(k, String.valueOf(def)));
    }
}
