# LineWatch

**DSL hattın ne zaman, ne kadar ve neden bozuluyor — kanıtıyla.**

[![Release](https://img.shields.io/github/v/release/byFischer/LineWatch?label=indir&color=2ea043)](https://github.com/byFischer/LineWatch/releases/latest)
[![CI](https://github.com/byFischer/LineWatch/actions/workflows/ci.yml/badge.svg)](https://github.com/byFischer/LineWatch/actions/workflows/ci.yml)
[![License](https://img.shields.io/github/license/byFischer/LineWatch?color=blue)](LICENSE)

LineWatch, TP-Link DSL modeminden 15 saniyede bir sinyal kalitesi (SNR), senkron
hızı, zayıflama ve hata sayaçlarını okur; SQLite'a kaydeder; grafiklerle gösterir ve
hat koptuğunda / sinyal düştüğünde / hata sayacı patladığında Windows bildirimi ve
Telegram mesajı gönderir. Sistem tepsisinde arka planda çalışır.


![LineWatch panosu](docs/img/Pano.jpg)

## İndir ve çalıştır

1. [**Son sürümü indir**](https://github.com/byFischer/LineWatch/releases/latest) →
   `LineWatch-x.y.z-windows-x64.zip`
2. Zip'i istediğin klasöre çıkar. **Java kurulumu gerekmez**, çalışma zamanı paketin
   içinde.
3. `LineWatch.exe`'ye çift tıkla.

> [!NOTE]
> **"Windows bilgisayarınızı korudu" uyarısı alman normal.** Uygulama kod imzalama
> sertifikasıyla imzalanmadığı için (sertifika yıllık ücretli) SmartScreen ilk
> çalıştırmada uyarır. **Ek bilgi** → **Yine de çalıştır** ile devam edebilirsin.
> İndirdiğin dosyanın bozulmadığını release'deki `SHA256SUMS.txt` ile doğrulayabilirsin.

Java'sı olanlar için `LineWatch-x.y.z.jar` da release'e ekleniyor:
`java -jar LineWatch-x.y.z.jar`

## Uyumluluk

| | |
|---|---|
| **Doğrulanmış modem** | TP-Link Archer VR400 (VDSL2) |
| **Muhtemelen çalışır** | Aynı `/cgi_gdpr` protokolünü kullanan diğer TP-Link VDSL modemleri — test edilmedi |
| **Çalışmaz** | Diğer markalar (Huawei, ZTE, Zyxel…), fiber/kablo modemler |
| **İşletim sistemi** | Windows 10 / 11 (x64) |

> [!IMPORTANT]
> Farklı bir TP-Link modelinde denediysen — çalışsın ya da çalışmasın —
> [issue açıp](https://github.com/byFischer/LineWatch/issues) modelini ve sonucu
> yazabilirsin. Desteklenen modem listesini genişletmenin en hızlı yolu bu.

## İlk kurulum

Uygulama ilk açılışta doğrudan **Ayarlar** ekranını gösterir:

1. **Modem adresi** — genelde `http://192.168.1.1` (varsayılan olarak dolu gelir).
2. **Modem şifresi** — modemin web arayüzüne girdiğin şifre. *Wi-Fi şifresi değil.*
3. **Bağlantıyı test et** → "bağlantı başarılı · VDSL2 · SNR 11.7 dB" gibi bir yanıt
   görmelisin.
4. **Kaydet ve uygula**.

> [!WARNING]
> Modem **5 yanlış şifre denemesinden sonra 10 dakika kilitlenir.** Şifreden emin
> değilsen önce tarayıcıdan modem arayüzüne girip doğrula.

> [!NOTE]
> Modem aynı anda tek web oturumu tutar. Tarayıcıdan modem arayüzüne girersen
> LineWatch'un oturumu düşer — uygulama bunu fark edip otomatik yeniden bağlanır,
> bir ölçüm turu gecikebilir.

Pencereyi kapatmak uygulamayı kapatmaz, tepsiye küçültür. Tamamen kapatmak için tepsi
simgesine sağ tıkla → **Çıkış**.

## Neler yapıyor

- **Pano** — anlık SNR / hız / zayıflama / CRC, hat sağlık puanı, uptime şeridi
- **Grafikler** — seçilebilir zaman aralığında SNR, sync hızı ve CRC artışı
- **Analiz** — 7×24 ısı haritası (hangi gün, hangi saatte bozuluyor), kopma öncesi
  10 dakikanın dökümü
- **Olaylar** — kopma/gelme, düşük SNR, CRC patlaması, retrain, internet erişimi
  kesintisi kayıtları
- **Bildirimler** — Windows toast + Telegram (hat koptuğunda internet olmadığı için
  Telegram mesajı kuyruğa alınır, hat gelince "X dk kesinti" bilgisiyle iletilir)
- **Modem yeniden başlatma** — sol menüdeki düğmeden

| Grafikler — SNR, senkron hızı ve CRC artışı | Analiz — hangi gün ve saatte bozuluyor |
|---|---|
| [![Grafikler](docs/img/Graphs.jpg)](docs/img/Graphs.jpg) | [![Analiz](docs/img/Analysis.jpg)](docs/img/Analysis.jpg) |

| Olaylar — kopma ve hata kayıtları | Ayarlar — eşikler ve bildirimler |
|---|---|
| [![Olaylar](docs/img/Logs.jpg)](docs/img/Logs.jpg) | [![Ayarlar](docs/img/Settings.jpg)](docs/img/Settings.jpg) |

Uyarı kuralları (Ayarlar'dan değiştirilebilir): örnekleme başına CRC artışı ≥ 50,
SNR < 6 dB (art arda 3 örnek), sync hızı değişimi (retrain), hat kopma/gelme.
CRC sayacı sıfırlanması (reboot) yok sayılır. Aynı tip bildirim 15 dakika içinde
tekrarlanmaz (kopma/gelme hariç).

## Telegram bildirimi kurulumu

1. Telegram'da **@BotFather**'a `/newbot` yaz, bot adını ver → sana bir **token** verir.
2. Yeni botunla bir kez konuşma başlat (**Start**'a bas).
3. **@userinfobot**'a mesaj atıp kendi **chat id**'ni öğren.
4. LineWatch → **Ayarlar** → token + chat id gir, "Telegram bildirimi"ni işaretle, kaydet.

Bot komutları (yalnızca ayarlı chat id'ye cevap verir): `/durum` anlık hat özeti,
`/olaylar` son 5 olay, `/yardim` komut listesi.

## Windows açılışında otomatik başlatma

**Ayarlar → Görünüm ve uygulama → "Bilgisayar açıldığında LineWatch otomatik başlasın"**
kutusunu işaretle. Startup klasörüne bir bat dosyası ekler/siler, kayıt defterine
dokunmaz.

## Ayarlar ve veriler nerede

| | |
|---|---|
| Ayarlar | `%APPDATA%\LineWatch\config.properties` |
| Veritabanı | `%APPDATA%\LineWatch\linewatch.db` (ham ölçümler 30 gün tutulur) |

Uygulamayı kaldırmak için klasörü sil; bu iki dosyayı da silersen geçmiş kaybolur.

---

## Geliştirme

Gereksinimler: **JDK 21+** (25 ile geliştiriliyor) ve **Maven 3.9+**.

```bash
mvn javafx:run                  # doğrudan çalıştır
mvn verify                      # derle + testler
mvn package                     # dağıtım jar'ı (JavaFX dahil, tek dosya)
```

Windows uygulama paketi (Java gerektirmeyen klasör) üretmek:

```bash
build-exe.bat                   # -> dist\LineWatch\LineWatch.exe
```

Collector'ı arayüz olmadan test etmek:

```bash
mvn compile
java -cp target/classes com.emre.linewatch.collector.CollectorMain <modem-şifresi> [http://192.168.1.1] [örnek-sayısı]
```

### Sürüm çıkarma

`v` ile başlayan bir tag push'lamak yeterli — [release workflow'u](.github/workflows/release.yml)
Windows paketini derleyip zipler, SHA256 alır ve GitHub Release'i açar:

```bash
git tag v0.1.0 && git push origin v0.1.0
```

Release açıklaması [`.github/release-notes-template.md`](.github/release-notes-template.md)
dosyasından üretilir.

### Mimari

```
Scheduler (15 sn) → TpLinkCollector (şifreli /cgi_gdpr) → LineSample
                  → SampleRepository (SQLite)
                  → AlertEngine → ToastNotifier + TelegramNotifier (kuyruklu)
JavaFX UI (Pano / Grafikler / Analiz / Olaylar / Ayarlar) ← canlı dinleyiciler + DB
```

Arayüzün dış bağımlılığı yok: temalar el yazımı CSS, ikonlar `SVGPath`, grafikler
`Canvas` üzerine çiziliyor. Tek çalışma zamanı bağımlılığı JavaFX ve sqlite-jdbc.

TP-Link GDPR protokolünün tamamı — login akışı, RSA/AES ayrıntıları, doğrulanmış
istek/yanıt örnekleri: [docs/modem-api.md](docs/modem-api.md)

## Katkı

Issue ve pull request'ler açık. En çok işe yarayacak katkılar:

- **Farklı TP-Link modellerinde test** — modelini ve sonucu issue'da paylaş
- **Yeni modem desteği** — `ModemCollector` arayüzünü uygulaman yeterli
- **Çeviri** — arayüz şu an yalnızca Türkçe

## Lisans

[MIT](LICENSE) — Emre Zeytin

---

<details>
<summary><b>English summary</b></summary>

**LineWatch** is a Windows desktop app that monitors a TP-Link DSL modem's line
quality. It polls the modem every 15 seconds over TP-Link's encrypted `/cgi_gdpr`
protocol, recording SNR margin, sync rate, attenuation and CRC/FEC/HEC error
counters into a local SQLite database. It charts the history, detects incidents
(line drops, CRC bursts, low SNR, retrains) and notifies you via Windows toast and
Telegram. It lives in the system tray.

The point is to answer *"why is my internet bad, and can I prove it to my ISP?"*

**Verified on the TP-Link Archer VR400 (VDSL2).** Other TP-Link VDSL models using the
same protocol may work but are untested — reports welcome in the issues.
Requires Windows 10/11 x64; no Java installation needed (the runtime is bundled).

The user interface is currently Turkish only. Grab the latest build from
[Releases](https://github.com/byFischer/LineWatch/releases/latest).

</details>
