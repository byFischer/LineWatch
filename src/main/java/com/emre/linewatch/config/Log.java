package com.emre.linewatch.config;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * java.util.logging uzerine kucuk yardimci. Paketlenmis .exe'de konsol olmadigi
 * icin hatalar donen dosyalara yazilir: {@code <dizin>/linewatch.0.log} (~1 MB x 3).
 *
 * <p>{@link #init(Path)} cagrilmadan once kayitlar yalnizca varsayilan konsol
 * isleyicisine gider (testler gercek %APPDATA% klasorune dokunmaz). Dizin
 * yazilamiyorsa sessizce dosyasiz devam edilir; bu sinif asla istisna firlatmaz.
 */
public final class Log {

    /** Uygulamanin tum logger'larinin atasi; GC'ye gitmesin diye guclu referans tutulur. */
    private static final Logger ROOT = Logger.getLogger("com.emre.linewatch");
    private static final int LIMIT_BYTES = 1_000_000;
    private static final int FILE_COUNT = 3;

    private static volatile FileHandler handler;

    private Log() {
    }

    public static Logger get(Class<?> type) {
        return Logger.getLogger(type.getName());
    }

    /** Dosya log'unu kurar; ikinci cagri etkisizdir. Basarili olursa true. */
    public static synchronized boolean init(Path dir) {
        if (handler != null) return true;
        try {
            Files.createDirectories(dir);
            // FileHandler deseninde '%' ozel karakter; dizin adinda gecerse kacirilir.
            String pattern = dir.toAbsolutePath().toString().replace('\\', '/').replace("%", "%%")
                    + "/linewatch.%g.log";
            FileHandler h = new FileHandler(pattern, LIMIT_BYTES, FILE_COUNT, true);
            h.setEncoding("UTF-8");
            h.setFormatter(new OneLineFormatter());
            h.setLevel(Level.INFO);
            ROOT.addHandler(h);
            handler = h;
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Yakalanmamis istisnalari log'a yazan varsayilan isleyiciyi kurar. */
    public static void installUncaughtHandler() {
        try {
            Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
                try {
                    ROOT.log(Level.SEVERE, "Yakalanmamis hata (" + t.getName() + ")", e);
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** Tek satir: zaman + seviye + logger + mesaj, varsa ardindan yigin izi. */
    static final class OneLineFormatter extends Formatter {
        private static final DateTimeFormatter TS =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

        @Override
        public String format(LogRecord r) {
            StringBuilder sb = new StringBuilder(160)
                    .append(TS.format(Instant.ofEpochMilli(r.getMillis()))).append(' ')
                    .append(r.getLevel().getName()).append(' ')
                    .append(r.getLoggerName()).append(" - ")
                    .append(formatMessage(r)).append(System.lineSeparator());
            if (r.getThrown() != null) {
                StringWriter sw = new StringWriter();
                r.getThrown().printStackTrace(new PrintWriter(sw));
                sb.append(sw);
            }
            return sb.toString();
        }
    }
}
