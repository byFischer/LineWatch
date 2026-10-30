package com.emre.linewatch.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.*;

class LogTest {

    @Test
    void initOnUnwritableDirectoryDoesNotThrow(@TempDir Path dir) throws Exception {
        // Dizin yerine ayni adli bir dosya: createDirectories basarisiz olur.
        Path blocker = dir.resolve("logs");
        Files.writeString(blocker, "dosya");
        assertDoesNotThrow(() -> Log.init(blocker));
        assertDoesNotThrow(() -> Log.get(LogTest.class).log(Level.WARNING, "deneme", new RuntimeException("x")));
    }

    @Test
    void formatterWritesOneLineWithStackTrace() {
        LogRecord r = new LogRecord(Level.WARNING, "Ag olcumu hatasi");
        r.setLoggerName("com.emre.linewatch.MonitorService");
        r.setThrown(new IllegalStateException("bozuk"));
        String out = new Log.OneLineFormatter().format(r);
        String first = out.lines().findFirst().orElseThrow();
        assertTrue(first.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} WARNING "
                + "com\\.emre\\.linewatch\\.MonitorService - Ag olcumu hatasi"), first);
        assertTrue(out.contains("IllegalStateException: bozuk"));
        assertTrue(out.contains("\tat "));
    }

    @Test
    void uncaughtHandlerDoesNotThrow() {
        Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
        try {
            Log.installUncaughtHandler();
            Thread.UncaughtExceptionHandler h = Thread.getDefaultUncaughtExceptionHandler();
            assertNotNull(h);
            assertDoesNotThrow(() -> h.uncaughtException(Thread.currentThread(), new RuntimeException("test")));
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(old);
        }
    }
}
