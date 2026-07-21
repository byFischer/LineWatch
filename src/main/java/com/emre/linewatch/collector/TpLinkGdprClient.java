package com.emre.linewatch.collector;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigInteger;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TP-Link "GDPR" sifreli web protokolu istemcisi (Archer VR400'de dogrulandi).
 * Akisin tamami: docs/modem-api.md
 */
public class TpLinkGdprClient {

    private final String host;          // "http://192.168.1.1"
    private final String password;
    private final HttpClient http;
    private final SecureRandom random = new SecureRandom();

    // oturum durumu
    private String sessionCookie;       // JSESSIONID=...
    private String token;
    private BigInteger rsaN, rsaE;
    private long seq;
    private String aesKey, aesIv;
    private String passwordHash;        // MD5("admin"+pwd)

    public TpLinkGdprClient(String host, String password) {
        this.host = host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
        this.password = password;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .cookieHandler(java.net.CookieHandler.getDefault() != null
                        ? java.net.CookieHandler.getDefault()
                        : new java.net.CookieManager())
                .build();
    }

    public synchronized boolean isLoggedIn() {
        return token != null;
    }

    public synchronized void login() throws IOException, InterruptedException {
        // 1) RSA parametreleri + seq
        String parm = post("/cgi/getParm?_=" + System.currentTimeMillis(), null, null);
        rsaE = new BigInteger(extract(parm, "ee=\"(\\w+)\""), 16);
        rsaN = new BigInteger(extract(parm, "nn=\"(\\w+)\""), 16);
        seq = Long.parseLong(extract(parm, "seq=\"(\\d+)\""));

        // 2) AES anahtari ve hash
        aesKey = gen16Digits();
        aesIv = gen16Digits();
        passwordHash = md5Hex("admin" + password);

        // 3) login istegi
        String data = aesEncrypt("admin\n" + password);
        String sign = rsaNoPad("key=" + aesKey + "&iv=" + aesIv + "&h=" + passwordHash
                + "&s=" + (seq + data.length()));
        String resp = post("/cgi/login?data=" + URLEncoder.encode(data, StandardCharsets.UTF_8)
                + "&sign=" + sign + "&Action=1&LoginStatus=0", null, null);
        if (!resp.contains("$.ret=0")) {
            token = null;
            throw new IOException("Login reddedildi: " + resp.trim());
        }

        // 4) token'i ana sayfadan al
        String index = get("/");
        Matcher m = Pattern.compile("token=\"?([A-Za-z0-9]+)\"?;").matcher(index);
        if (!m.find()) {
            token = null;
            throw new IOException("Login sonrasi token bulunamadi");
        }
        token = m.group(1);
    }

    /**
     * Sifreli ACT istegi gonderir, cozulmus duz metin yaniti dondurur.
     * Yanit "[error]0" ile bitmiyorsa IOException firlatir.
     */
    public synchronized String act(String plaintextPayload) throws IOException, InterruptedException {
        if (token == null) login();
        String data = aesEncrypt(plaintextPayload);
        String sign = rsaNoPad("h=" + passwordHash + "&s=" + (seq + data.length()));
        String body = "sign=" + sign + "\r\ndata=" + data + "\r\n";
        String resp = post("/cgi_gdpr", body, token);
        String plain = aesDecrypt(resp.trim());
        Matcher err = Pattern.compile("\\[error](\\d+)").matcher(plain);
        if (err.find() && !"0".equals(err.group(1))) {
            throw new IOException("Modem hata kodu dondu: " + err.group(1));
        }
        return plain;
    }

    /** Oturum dustuyse (baska tarayici girisi vs.) cagiran taraf bunu cagirip yeniden dener. */
    public synchronized void invalidateSession() {
        token = null;
    }

    /**
     * Modemi yeniden baslatir. Iki bilinen varyanti dener (ACT_OP + CGI).
     * Modem reboot'a girdigi icin baglantinin kopmasi/timeout normaldir -> basari sayilir.
     */
    public synchronized void reboot() throws IOException, InterruptedException {
        try {
            act("7\r\n[ACT_REBOOT#0,0,0,0,0,0#0,0,0,0,0,0]0,0\r\n");
        } catch (IOException first) {
            if (isConnectionDrop(first)) { token = null; return; } // reboot basladi
            try {
                act("8\r\n[/cgi/reboot#0,0,0,0,0,0#0,0,0,0,0,0]0,0\r\n");
            } catch (IOException second) {
                if (isConnectionDrop(second)) { token = null; return; }
                throw second;
            }
        }
        token = null;
    }

    private static boolean isConnectionDrop(IOException e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        return m.contains("ulasilamiyor") || m.contains("timed out") || m.contains("GOAWAY")
                || m.contains("reset") || m.contains("closed");
    }

    public synchronized void logout() {
        try {
            act("8\r\n[/cgi/logout#0,0,0,0,0,0#0,0,0,0,0,0]0,0\r\n");
        } catch (Exception ignored) {
        } finally {
            token = null;
        }
    }

    // ---- HTTP ----

    private String get(String path) throws IOException, InterruptedException {
        HttpRequest req = base(path).GET().build();
        return send(req);
    }

    private String post(String path, String body, String tokenId) throws IOException, InterruptedException {
        HttpRequest.Builder b = base(path);
        if (tokenId != null) b.header("TokenID", tokenId);
        b.header("Content-Type", "text/plain");
        b.POST(body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return send(b.build());
    }

    private HttpRequest.Builder base(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(host + path))
                .timeout(Duration.ofSeconds(8))
                .header("Referer", host + "/")
                .header("User-Agent", "LineWatch/0.1");
        if (sessionCookie != null) b.header("Cookie", sessionCookie);
        return b;
    }

    private String send(HttpRequest req) throws IOException, InterruptedException {
        HttpResponse<String> r;
        try {
            r = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (ConnectException | java.net.http.HttpTimeoutException e) {
            throw new IOException("Modeme ulasilamiyor: " + e.getMessage(), e);
        }
        r.headers().firstValue("set-cookie").ifPresent(c -> {
            String v = c.split(";", 2)[0];
            if (v.startsWith("JSESSIONID=") && !v.contains("deleted")) sessionCookie = v;
        });
        if (r.statusCode() != 200) {
            throw new IOException("HTTP " + r.statusCode() + " (" + req.uri().getPath() + ")");
        }
        return r.body();
    }

    // ---- kripto ----

    private String gen16Digits() {
        StringBuilder sb = new StringBuilder(16);
        sb.append(System.currentTimeMillis());
        while (sb.length() < 16) sb.append(random.nextInt(10));
        return sb.substring(0, 16);
    }

    private String aesEncrypt(String plaintext) throws IOException {
        try {
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(aesKey.getBytes(StandardCharsets.UTF_8), "AES"),
                    new IvParameterSpec(aesIv.getBytes(StandardCharsets.UTF_8)));
            return Base64.getEncoder().encodeToString(c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IOException("AES sifreleme hatasi", e);
        }
    }

    private String aesDecrypt(String base64) throws IOException {
        try {
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(aesKey.getBytes(StandardCharsets.UTF_8), "AES"),
                    new IvParameterSpec(aesIv.getBytes(StandardCharsets.UTF_8)));
            return new String(c.doFinal(Base64.getDecoder().decode(base64)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IOException("AES cozme hatasi (oturum dusmus olabilir)", e);
        }
    }

    /** 64-byte sifir dolgulu bloklar, hex ciktisi blok basina 128 karaktere sol-sifirlanir. */
    private String rsaNoPad(String s) {
        byte[] all = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        for (int off = 0; off < all.length; off += 64) {
            byte[] block = new byte[64];
            System.arraycopy(all, off, block, 0, Math.min(64, all.length - off));
            BigInteger m = new BigInteger(1, block);
            String hex = m.modPow(rsaE, rsaN).toString(16);
            out.append("0".repeat(Math.max(0, 128 - hex.length()))).append(hex);
        }
        return out.toString();
    }

    private static String md5Hex(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String extract(String text, String regex) throws IOException {
        Matcher m = Pattern.compile(regex).matcher(text);
        if (!m.find()) throw new IOException("Beklenen deger bulunamadi: " + regex);
        return m.group(1);
    }
}
