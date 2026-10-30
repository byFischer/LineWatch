package com.emre.linewatch.config;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Pencere tepsideyken sureci Windows'un calisma kumesinden (fiziksel RAM)
 * cikarir: SetProcessWorkingSetSize(-1, -1). Dokunulmayan sayfalar (JavaFX'in
 * Direct3D tamponlari, kullanilmayan kod) bekleme listesine gider; gerektiginde
 * isletim sistemi geri yukler. Gorev Yoneticisi'ndeki "Bellek" degeri buna gore duser.
 *
 * <p>Windows disinda ya da cagri basarisizsa sessizce hicbir sey yapmaz.
 */
public final class MemoryTrim {

    private static final Logger LOG = Log.get(MemoryTrim.class);
    private static final MethodHandle SET_WORKING_SET = load();

    private MemoryTrim() {
    }

    /** @return calisma kumesi bosaltildiysa true */
    public static boolean trimWorkingSet() {
        if (SET_WORKING_SET == null) return false;
        try {
            // GetCurrentProcess() sahte tutamaci -1; boyutlar (SIZE_T)-1 = "bosalt"
            int ok = (int) SET_WORKING_SET.invoke(MemorySegment.ofAddress(-1L), -1L, -1L);
            return ok != 0;
        } catch (Throwable e) {
            LOG.log(Level.FINE, "Calisma kumesi bosaltilamadi", e);
            return false;
        }
    }

    private static MethodHandle load() {
        if (!System.getProperty("os.name", "").toLowerCase().startsWith("windows")) return null;
        try {
            SymbolLookup kernel = SymbolLookup.libraryLookup("kernel32", Arena.global());
            return Linker.nativeLinker().downcallHandle(kernel.findOrThrow("SetProcessWorkingSetSize"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG));
        } catch (Throwable e) {
            LOG.log(Level.FINE, "SetProcessWorkingSetSize yuklenemedi", e);
            return null;
        }
    }
}
