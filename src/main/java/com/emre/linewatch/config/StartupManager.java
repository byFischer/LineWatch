package com.emre.linewatch.config;

import java.io.IOException;
import java.net.URISyntaxException;
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

    private StartupManager() {}

    public static boolean isEnabled() {
        return Files.exists(BAT);
    }

    public static void setEnabled(boolean enabled) throws IOException {
        if (!enabled) {
            Files.deleteIfExists(BAT);
            return;
        }
        // jpackage imajindan calisiyorsak exe'yi, degilse jar'i baslat
        String appPath = System.getProperty("jpackage.app-path");
        String cmd = appPath != null
                ? "start \"\" \"" + appPath + "\""
                : "start \"\" javaw -Xms32m -Xmx192m -XX:+UseSerialGC "
                  + "-XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 -jar \"" + jarPath() + "\"";
        Files.writeString(BAT, "@echo off\r\n" + cmd + "\r\n");
    }

    /** Calisan jar'in yolu; gelistirme ortaminda (target/classes) dagitim jar'ina duser. */
    private static String jarPath() {
        try {
            Path loc = Path.of(StartupManager.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (loc.toString().endsWith(".jar")) return loc.toString();
            // mvn javafx:run ile calisiyorsak: target/classes -> target/linewatch-*-jar-with-dependencies.jar
            Path target = loc.getParent();
            try (var stream = Files.list(target)) {
                return stream.filter(p -> p.toString().endsWith("jar-with-dependencies.jar"))
                        .findFirst().map(Path::toString)
                        .orElse(target.resolve("linewatch-0.1.0-jar-with-dependencies.jar").toString());
            }
        } catch (URISyntaxException | IOException e) {
            return "linewatch-0.1.0-jar-with-dependencies.jar";
        }
    }
}
