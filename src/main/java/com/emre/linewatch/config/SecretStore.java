package com.emre.linewatch.config;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Sifre/token gibi gizli degerleri Windows DPAPI (CryptProtectData, mevcut
 * kullanici kapsami) ile sifreler. Sifreli deger {@code dpapi:<base64>} olarak saklanir.
 *
 * <p>DPAPI yoksa (Windows disi) ya da hata verirse duz metne duser; hicbir
 * durumda istisna firlatmaz. Gizli degerler asla log'a yazilmaz.
 */
public final class SecretStore {

    public static final String PREFIX = "dpapi:";

    private static final Logger LOG = Log.get(SecretStore.class);
    /** Ayni kullanicinin baska uygulamalari blob'u kolayca cozemesin diye ek entropi. */
    private static final byte[] ENTROPY = "LineWatch".getBytes(StandardCharsets.US_ASCII);
    private static final int CRYPTPROTECT_UI_FORBIDDEN = 0x1;

    private SecretStore() {
    }

    /** DPAPI bu sistemde kullanilabilir mi. */
    public static boolean isAvailable() {
        return Native.AVAILABLE;
    }

    /** Deger DPAPI ile sifrelenmis bicimde mi. */
    public static boolean isProtected(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    /** Duz metni saklanacak bicime cevirir; DPAPI yoksa ya da hata olursa duz metni dondurur. */
    public static String protect(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        if (!isAvailable()) return plain;
        byte[] data = plain.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] blob = Native.transform(true, data);
            return PREFIX + Base64.getEncoder().encodeToString(blob);
        } catch (Throwable e) {
            LOG.log(Level.WARNING, "DPAPI sifreleme basarisiz, deger duz metin saklanacak: " + e);
            return plain;
        } finally {
            Arrays.fill(data, (byte) 0);
        }
    }

    /**
     * Saklanan degeri duz metne cevirir. Onekli degilse eski (duz metin) kayittir
     * ve aynen dondurulur. Cozulemezse bos dizgi dondurur.
     */
    public static String reveal(String stored) {
        if (stored == null || stored.isEmpty()) return "";
        if (!isProtected(stored)) return stored;
        if (!isAvailable()) {
            LOG.warning("DPAPI yok, sifreli ayar cozulemedi");
            return "";
        }
        try {
            byte[] blob = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] plain = Native.transform(false, blob);
            try {
                return new String(plain, StandardCharsets.UTF_8);
            } finally {
                Arrays.fill(plain, (byte) 0);
            }
        } catch (Throwable e) {
            LOG.log(Level.WARNING, "DPAPI cozme basarisiz (baska kullanici/bilgisayar olabilir): " + e);
            return "";
        }
    }

    /** crypt32/kernel32 baglantilari; ilk kullanimda yuklenir, basarisizsa AVAILABLE=false. */
    private static final class Native {
        static final boolean AVAILABLE;
        static final MethodHandle PROTECT;
        static final MethodHandle UNPROTECT;
        static final MethodHandle LOCAL_FREE;
        /** DATA_BLOB { DWORD cbData; BYTE* pbData; } (x64: 4 bayt dolgu) */
        static final StructLayout BLOB = MemoryLayout.structLayout(
                JAVA_INT.withName("cbData"),
                MemoryLayout.paddingLayout(4),
                ADDRESS.withName("pbData"));
        static final long CB = BLOB.byteOffset(MemoryLayout.PathElement.groupElement("cbData"));
        static final long PB = BLOB.byteOffset(MemoryLayout.PathElement.groupElement("pbData"));

        static {
            MethodHandle p = null, u = null, f = null;
            boolean ok = false;
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.startsWith("windows") && ADDRESS.byteSize() == 8) {
                try {
                    Linker linker = Linker.nativeLinker();
                    SymbolLookup crypt = SymbolLookup.libraryLookup("crypt32", Arena.global());
                    SymbolLookup kernel = SymbolLookup.libraryLookup("kernel32", Arena.global());
                    // BOOL Crypt[Un]ProtectData(DATA_BLOB*, LPCWSTR/LPWSTR*, DATA_BLOB*, PVOID,
                    //                           PROMPTSTRUCT*, DWORD, DATA_BLOB*)
                    FunctionDescriptor fd = FunctionDescriptor.of(JAVA_INT,
                            ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS);
                    p = linker.downcallHandle(crypt.findOrThrow("CryptProtectData"), fd);
                    u = linker.downcallHandle(crypt.findOrThrow("CryptUnprotectData"), fd);
                    f = linker.downcallHandle(kernel.findOrThrow("LocalFree"),
                            FunctionDescriptor.of(ADDRESS, ADDRESS));
                    ok = true;
                } catch (Throwable e) {
                    Log.get(SecretStore.class).log(Level.WARNING, "DPAPI yuklenemedi: " + e);
                }
            }
            PROTECT = p;
            UNPROTECT = u;
            LOCAL_FREE = f;
            AVAILABLE = ok;
        }

        static byte[] transform(boolean protect, byte[] input) throws Throwable {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment in = blob(arena, input);
                MemorySegment entropy = blob(arena, ENTROPY);
                MemorySegment out = arena.allocate(BLOB);
                MethodHandle fn = protect ? PROTECT : UNPROTECT;
                int ok = (int) fn.invoke(in, MemorySegment.NULL, entropy, MemorySegment.NULL,
                        MemorySegment.NULL, CRYPTPROTECT_UI_FORBIDDEN, out);
                if (ok == 0) throw new IllegalStateException(protect ? "CryptProtectData" : "CryptUnprotectData");
                int len = out.get(JAVA_INT, CB);
                MemorySegment data = out.get(ADDRESS, PB).reinterpret(len);
                try {
                    byte[] result = data.toArray(JAVA_BYTE);
                    data.fill((byte) 0);
                    return result;
                } finally {
                    MemorySegment ignored = (MemorySegment) LOCAL_FREE.invoke(data);
                }
            }
        }

        private static MemorySegment blob(Arena arena, byte[] bytes) {
            MemorySegment data = arena.allocate(Math.max(1, bytes.length));
            MemorySegment.copy(bytes, 0, data, JAVA_BYTE, 0, bytes.length);
            MemorySegment b = arena.allocate(BLOB);
            b.set(JAVA_INT, CB, bytes.length);
            b.set(ADDRESS, PB, data);
            return b;
        }
    }
}
