package com.emre.linewatch.config;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Arayuz metinleri. Ceviriler
 * {@code resources/com/emre/linewatch/i18n/messages_{tr,en}.properties}
 * dosyalarinda tutulur; Java 9'dan beri .properties dosyalari UTF-8 okundugu
 * icin Turkce karakterler kacis dizisi olmadan yazilabilir.
 *
 * <p>config paketinde duruyor cunku yalnizca ui degil, alert (bildirim metinleri)
 * ve storage (olay etiketleri) katmanlari da ceviriye ihtiyac duyuyor.
 */
public final class I18n {

    /** Dil tercihi olarak "sistemle ayni" anlamina gelen deger. */
    public static final String AUTO = "auto";

    private static final String BASE = "com.emre.linewatch.i18n.messages";
    private static final Locale TR = Locale.forLanguageTag("tr-TR");
    private static final Locale EN = Locale.ENGLISH;

    private static volatile Locale locale = resolve(AUTO);
    private static volatile ResourceBundle bundle = ResourceBundle.getBundle(BASE, locale);

    private I18n() {
    }

    /** @param tag "tr", "en" ya da {@link #AUTO} */
    public static void setLanguage(String tag) {
        Locale next = resolve(tag);
        if (next.equals(locale)) return;
        locale = next;
        bundle = ResourceBundle.getBundle(BASE, next);
    }

    /** Sayi ve tarih bicimlemesi de bu locale ile yapilmali (bkz. ui.Fmt). */
    public static Locale locale() {
        return locale;
    }

    public static boolean isTurkish() {
        return "tr".equals(locale.getLanguage());
    }

    public static String t(String key) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            // Eksik ceviri uygulamayi durdurmasin; anahtar ekranda gorunsun ki fark edilsin
            return "!" + key + "!";
        }
    }

    /** Yer tutucular {@link String#format} sozdizimindedir (%s, %d) — dogru locale ile bicimlenir. */
    public static String t(String key, Object... args) {
        return String.format(locale, t(key), args);
    }

    private static Locale resolve(String tag) {
        if (tag == null || tag.isBlank() || AUTO.equalsIgnoreCase(tag)) {
            return "tr".equals(Locale.getDefault().getLanguage()) ? TR : EN;
        }
        return "tr".equalsIgnoreCase(tag) ? TR : EN;
    }
}
