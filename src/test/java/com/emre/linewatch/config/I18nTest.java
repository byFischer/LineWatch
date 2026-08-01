package com.emre.linewatch.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dil paketlerini birbirine karsi dogrular. Eksik anahtar ya da uyusmayan
 * yer tutucu, ancak o ekran acildiginda ortaya cikan bir hata olurdu —
 * burada derleme zamaninda yakalanir.
 */
class I18nTest {

    /** %s, %d, %,.1f, %2$s ... ("%%" literal yuzde isaretidir, sayilmaz) */
    private static final Pattern SPEC =
            Pattern.compile("%(?:(\\d+)\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?([a-zA-Z])");

    @AfterEach
    void restoreLanguage() {
        I18n.setLanguage(I18n.AUTO);
    }

    private static Properties load(String lang) throws IOException {
        Properties p = new Properties();
        String path = "/com/emre/linewatch/i18n/messages_" + lang + ".properties";
        try (InputStream in = I18nTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path + " bulunamadi");
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    @Test
    void bothBundlesDefineExactlyTheSameKeys() throws IOException {
        var tr = new TreeSet<>(load("tr").stringPropertyNames());
        var en = new TreeSet<>(load("en").stringPropertyNames());

        var missingInEn = new TreeSet<>(tr);
        missingInEn.removeAll(en);
        var missingInTr = new TreeSet<>(en);
        missingInTr.removeAll(tr);

        assertTrue(missingInEn.isEmpty(), "Ingilizce pakette eksik: " + missingInEn);
        assertTrue(missingInTr.isEmpty(), "Turkce pakette eksik: " + missingInTr);
        assertFalse(tr.isEmpty(), "dil paketi bos");
    }

    @Test
    void placeholderCountsMatchAcrossLanguages() throws IOException {
        Properties tr = load("tr");
        Properties en = load("en");
        List<String> problems = new ArrayList<>();

        for (String key : new TreeSet<>(tr.stringPropertyNames())) {
            int a = argCount(tr.getProperty(key));
            int b = argCount(en.getProperty(key));
            if (a != b) problems.add(key + " (tr=" + a + ", en=" + b + ")");
        }
        assertTrue(problems.isEmpty(),
                "Yer tutucu sayisi dillere gore degisiyor, bicimleme calisma aninda patlar: " + problems);
    }

    @Test
    void everyMessageFormatsWithoutError() throws IOException {
        for (String lang : List.of("tr", "en")) {
            I18n.setLanguage(lang);
            Properties bundle = load(lang);
            for (String key : new TreeSet<>(bundle.stringPropertyNames())) {
                String pattern = bundle.getProperty(key);
                Object[] args = dummyArgs(pattern);
                assertDoesNotThrow(() -> String.format(I18n.locale(), pattern, args),
                        lang + " / " + key + " bicimlendirilemedi: " + pattern);
            }
        }
    }

    @Test
    void turkishAndEnglishReturnDifferentTextForTheSameKey() {
        I18n.setLanguage("tr");
        assertEquals("Pano", I18n.t("nav.dashboard"));
        assertTrue(I18n.isTurkish());

        I18n.setLanguage("en");
        assertEquals("Dashboard", I18n.t("nav.dashboard"));
        assertFalse(I18n.isTurkish());
    }

    @Test
    void percentSignFollowsLanguageConvention() {
        I18n.setLanguage("tr");
        assertEquals("%98", I18n.t("fmt.percent", "98"), "Turkcede yuzde isareti onde");

        I18n.setLanguage("en");
        assertEquals("98%", I18n.t("fmt.percent", "98"), "Ingilizcede yuzde isareti arkada");
    }

    @Test
    void numbersUseTheLocaleDecimalSeparator() {
        I18n.setLanguage("tr");
        assertEquals("SNR marjı 11,7 dB (eşik: 6,0 dB)", I18n.t("alert.lowSnr", 11.7, 6.0));

        I18n.setLanguage("en");
        assertEquals("SNR margin 11.7 dB (threshold: 6.0 dB)", I18n.t("alert.lowSnr", 11.7, 6.0));
    }

    @Test
    void unknownKeyIsVisibleInsteadOfCrashing() {
        assertEquals("!yok.boyle.bir.anahtar!", I18n.t("yok.boyle.bir.anahtar"));
    }

    @Test
    void unknownLanguageTagFallsBackToEnglish() {
        I18n.setLanguage("de");
        assertEquals(Locale.ENGLISH.getLanguage(), I18n.locale().getLanguage());
    }

    // ---- yardimcilar ----

    /** Bicim dizisindeki bagimsiz degisken sayisi; pozisyonel kullanimda en buyuk indeks. */
    private static int argCount(String pattern) {
        int sequential = 0;
        int highestIndex = 0;
        Matcher m = SPEC.matcher(pattern);
        int i = 0;
        while (i < pattern.length()) {
            if (pattern.charAt(i) == '%') {
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '%') {
                    i += 2;
                    continue;
                }
                if (m.find(i) && m.start() == i) {
                    if (m.group(1) != null) highestIndex = Math.max(highestIndex, Integer.parseInt(m.group(1)));
                    else sequential++;
                    i = m.end();
                    continue;
                }
            }
            i++;
        }
        return Math.max(sequential, highestIndex);
    }

    /** Bicim dizisinin bekledigi turlere uygun ornek degerler uretir. */
    private static Object[] dummyArgs(String pattern) {
        List<Object> args = new ArrayList<>();
        Matcher m = SPEC.matcher(pattern);
        int i = 0;
        while (i < pattern.length()) {
            if (pattern.charAt(i) == '%') {
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '%') {
                    i += 2;
                    continue;
                }
                if (m.find(i) && m.start() == i) {
                    args.add(switch (Character.toLowerCase(m.group(2).charAt(0))) {
                        case 'd' -> 1;
                        case 'f', 'e', 'g' -> 1.0;
                        default -> "x";
                    });
                    i = m.end();
                    continue;
                }
            }
            i++;
        }
        return args.toArray();
    }
}
