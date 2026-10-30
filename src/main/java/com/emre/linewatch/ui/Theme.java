package com.emre.linewatch.ui;

import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableValue;
import javafx.scene.Scene;
import javafx.scene.paint.Color;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Acik/koyu tema yonetimi.
 *
 * <p>Renkler iki yerde tanimli: CSS tarafi {@code styles.css} icinde (-lw-* degiskenleri),
 * Java tarafi ise burada {@link Palette} olarak. Canvas ile cizilen bilesenler (grafikler,
 * sparkline, isi haritasi) CSS'i okuyamadigi icin bu ikinci kopya zorunlu — iki listeyi
 * birlikte guncelle.
 *
 * <p>Sistem temasi once JavaFX 22+ {@code Platform.getPreferences()} API'siyle aranir
 * (canli takip eder). Bulunamazsa Windows kayit defterinden tek seferlik okunur; bu
 * durumda {@link #refreshSystemTheme()} pencere odaklandiginda cagrilir.
 */
public final class Theme {

    public enum Mode {
        AUTO("theme.auto"), DARK("theme.dark"), LIGHT("theme.light");

        private final String key;

        Mode(String key) { this.key = key; }

        public String label() { return com.emre.linewatch.config.I18n.t(key); }
    }

    /** Canvas cizimleri icin renk paleti; CSS'teki -lw-* degiskenlerinin aynasi. */
    public record Palette(
            Color bg, Color surface, Color surfaceAlt, Color border, Color grid,
            Color text, Color textMuted, Color textFaint,
            Color accent, Color good, Color warn, Color bad,
            Color down, Color up, Color heatFrom, Color heatTo) {
    }

    public static final Palette DARK = new Palette(
            Color.web("#0e1013"), Color.web("#16191f"), Color.web("#1c2027"),
            Color.web("#24282f"), Color.web("#1e222a"),
            Color.web("#e8eaed"), Color.web("#9aa2ae"), Color.web("#6b7280"),
            Color.web("#5c7cfa"), Color.web("#3ecf8e"), Color.web("#e8a33d"), Color.web("#e5484d"),
            Color.web("#5c7cfa"), Color.web("#e8a33d"),
            Color.web("#161a22"), Color.web("#8aa6ff"));

    public static final Palette LIGHT = new Palette(
            Color.web("#f7f8fa"), Color.web("#ffffff"), Color.web("#f0f2f5"),
            Color.web("#e3e6ea"), Color.web("#eceef2"),
            Color.web("#16191f"), Color.web("#5a6472"), Color.web("#858e9c"),
            Color.web("#4263eb"), Color.web("#12915c"), Color.web("#b7791f"), Color.web("#d33741"),
            Color.web("#4263eb"), Color.web("#c2761a"),
            Color.web("#eef1f6"), Color.web("#2f4fd0"));

    private static final ObjectProperty<Palette> PALETTE = new SimpleObjectProperty<>(DARK);
    private static final List<Scene> SCENES = new ArrayList<>();

    private static Mode mode = Mode.AUTO;
    private static boolean systemDark = true;
    private static boolean nativeWatchActive;

    private Theme() {
    }

    /** Sahneyi temaya baglar: stil dosyasini ekler ve mevcut temayi uygular. */
    public static void install(Scene scene) {
        scene.getStylesheets().add(Theme.class.getResource("/com/emre/linewatch/styles.css").toExternalForm());
        SCENES.add(scene);
        hookNativePreferences();
        if (!nativeWatchActive) systemDark = readWindowsDark(systemDark);
        apply();
    }

    public static Mode mode() { return mode; }

    public static void setMode(Mode m) {
        mode = m == null ? Mode.AUTO : m;
        if (mode == Mode.AUTO && !nativeWatchActive) systemDark = readWindowsDark(systemDark);
        apply();
    }

    public static boolean isDark() {
        return switch (mode) {
            case DARK -> true;
            case LIGHT -> false;
            case AUTO -> systemDark;
        };
    }

    public static Palette palette() { return PALETTE.get(); }

    /** Canvas bilesenleri buna dinleyici takip tema degisince kendini yeniden cizer. */
    public static ReadOnlyObjectProperty<Palette> paletteProperty() { return PALETTE; }

    /**
     * Tema degisince {@code action} calisir; dinleyici {@code owner} dugumu yasadigi
     * surece gecerlidir. PALETTE statik oldugu icin dogrudan addListener ile eklenen
     * lambda dugumu (ve tum sayfayi) sonsuza dek bellekte tutardi; burada Theme'e
     * zayif baglanir, guclu referansi dugumun kendisi tasir.
     */
    public static void onPaletteChange(javafx.scene.Node owner, java.util.function.Consumer<Palette> action) {
        javafx.beans.value.ChangeListener<Palette> listener = (obs, old, now) -> action.accept(now);
        owner.getProperties().put(listener, Boolean.TRUE);
        PALETTE.addListener(new javafx.beans.value.WeakChangeListener<>(listener));
    }

    /**
     * Yerel tema takibi yoksa (JavaFX 21) sistem temasini yeniden okur.
     * Pencere odaklandiginda cagrilir; kullanici Windows temasini degistirip
     * uygulamaya dondugunde arayuz uyum saglar.
     */
    public static void refreshSystemTheme() {
        if (nativeWatchActive || mode != Mode.AUTO) return;
        boolean now = readWindowsDark(systemDark);
        if (now != systemDark) {
            systemDark = now;
            apply();
        }
    }

    private static void apply() {
        boolean dark = isDark();
        PALETTE.set(dark ? DARK : LIGHT);
        for (Scene s : SCENES) {
            if (s.getRoot() == null) continue;
            var classes = s.getRoot().getStyleClass();
            classes.remove("theme-light");
            if (!dark) classes.add("theme-light");
        }
    }

    /**
     * JavaFX 22+ {@code Platform.getPreferences().colorSchemeProperty()} varsa ona baglanir.
     * Yansima kullaniliyor cunku proje JavaFX 21 ile derleniyor; API orada henuz yok.
     */
    private static void hookNativePreferences() {
        if (nativeWatchActive) return;
        try {
            Method getPreferences = Platform.class.getMethod("getPreferences");
            Object prefs = getPreferences.invoke(null);
            Class<?> prefsType = Class.forName("javafx.application.Platform$Preferences");
            Method colorScheme = prefsType.getMethod("colorSchemeProperty");
            Object value = colorScheme.invoke(prefs);
            if (!(value instanceof ObservableValue<?> observable)) return;

            systemDark = isDarkScheme(observable.getValue());
            observable.addListener((obs, old, now) -> {
                systemDark = isDarkScheme(now);
                if (mode == Mode.AUTO) Platform.runLater(Theme::apply);
            });
            nativeWatchActive = true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // JavaFX 21: yerel API yok, kayit defteri yedegi kullanilacak
        }
    }

    private static boolean isDarkScheme(Object colorScheme) {
        return colorScheme != null && colorScheme.toString().toUpperCase().contains("DARK");
    }

    /**
     * Windows'un uygulama temasi tercihi: AppsUseLightTheme 0 = koyu, 1 = acik.
     * Okunamazsa {@code fallback} dondurur.
     */
    private static boolean readWindowsDark(boolean fallback) {
        try {
            Process p = new ProcessBuilder("reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "/v", "AppsUseLightTheme")
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return fallback;
            }
            int i = out.lastIndexOf("0x");
            if (i < 0 || i + 2 >= out.length()) return fallback;
            return out.charAt(i + 2) == '0';
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}
