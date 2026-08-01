package com.emre.linewatch.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Windows baslangicinda otomatik calisma: Startup klasorune bat dosyasi
 * koyar/siler. Kayit defterine dokunmaz, kaldirmasi kolay.
 */
public final class StartupManager {

    private static final Path STARTUP_DIR = Path.of(System.getenv("APPDATA"),
            "Microsoft", "Windows", "Start Menu", "Programs", "Startup");
    private static final Path BAT = STARTUP_DIR.resolve("LineWatch.bat");
    private static final String JVM_OPTS =
            "-Xms32m -Xmx192m -XX:+UseSerialGC -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30";

    private StartupManager() {}

    public static boolean isEnabled() {
        return Files.exists(BAT);
    }

    public static void setEnabled(boolean enabled) throws IOException {
        if (!enabled) {
            Files.deleteIfExists(BAT);
            return;
        }
        Files.createDirectories(STARTUP_DIR);
        Files.writeString(BAT, script());
    }

    private static String script() {
        // jpackage imajindan calisiyorsak exe yolu surumden bagimsizdir
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null) {
            return "@echo off\r\nstart \"\" \"" + appPath + "\"\r\n";
        }
        // Gelistirme kurulumu: jar adi surum numarasi icerir. Yol sabit yazilirsa
        // surum yukseltildiginde bat hala eski jar'i baslatir (ve o silinmisse hic
        // acilmaz), bu yuzden jar her acilista klasorden bulunur.
        String dir = jarDir();
        return "@echo off\r\n"
                + "rem LineWatch otomatik baslatma - jar surumden bagimsiz bulunur\r\n"
                + "setlocal\r\n"
                + "set \"JAR=\"\r\n"
                + "for %%f in (\"" + dir + "\\*-jar-with-dependencies.jar\") do set \"JAR=%%~ff\"\r\n"
                + "if not defined JAR exit /b 1\r\n"
                + "start \"\" javaw " + JVM_OPTS + " -jar \"%JAR%\"\r\n";
    }

    /**
     * Dagitim jar'inin bulundugu klasor. Jar'dan calisiyorsak jar'in klasoru,
     * {@code mvn javafx:run} ile calisiyorsak {@code target/classes}'in ustu —
     * ikisinde de {@code getParent()} dogru klasoru veriyor.
     */
    private static String jarDir() {
        try {
            Path loc = Path.of(StartupManager.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path dir = loc.getParent();
            return dir == null ? "." : dir.toString();
        } catch (Exception e) {
            return ".";
        }
    }
}
