# LineWatch

TP-Link Archer VR400 için DSL hat izleme masaüstü uygulaması. Arka planda (system tray)
çalışır, modemden 15 saniyede bir SNR / attenuation / sync hızı / CRC verisi toplar,
SQLite'a kaydeder, JavaFX grafiklerle gösterir ve kopma / CRC patlaması / düşük SNR
durumlarında Windows toast + Telegram bildirimi gönderir.

## Çalıştırma

En kolayı: `build-exe.bat` çalıştır → `dist\LineWatch\LineWatch.exe` (Java kurulumu gerektirmez).

Geliştirme: `mvn javafx:run` — veya `LineWatch.bat` (jar ile, bellek limitli).

Dağıtım jar'ı (tek dosya, JavaFX dahil):

```
mvn package
java -jar target/linewatch-0.1.0-jar-with-dependencies.jar
```

Collector'ı UI olmadan test etmek:

```
mvn compile
java -cp target/classes com.emre.linewatch.collector.CollectorMain <modem-şifresi> [http://192.168.1.1] [örnek-sayısı]
```

## Ayarlar / veriler

- Ayarlar: `%APPDATA%\LineWatch\config.properties` (uygulamadaki **Ayarlar** sekmesinden de düzenlenir)
- Veritabanı: `%APPDATA%\LineWatch\linewatch.db` (ham örnekler 30 gün tutulur)

## Telegram bildirimi kurulumu

1. Telegram'da **@BotFather**'a `/newbot` yaz, bot adını ver → sana bir **token** verir.
2. Yeni botunla bir kez konuşma başlat (**Start**'a bas).
3. **@userinfobot**'a mesaj atıp kendi **chat id**'ni öğren.
4. LineWatch → Ayarlar → token + chat id gir, "Telegram bildirimi"ni işaretle, kaydet.

Hat koptuğunda internet olmadığı için Telegram mesajı kuyruğa alınır; hat dönünce
"hat geri geldi (X dk kesinti)" mesajıyla birlikte telefona ulaşır.

Bot komutları (sadece ayarlı chat id'ye cevap verir): `/durum` anlık hat özeti,
`/olaylar` son 5 olay, `/yardim` komut listesi.

## Windows açılışında otomatik başlatma

Uygulama içinden: **Ayarlar → Uygulama → "Bilgisayar açıldığında LineWatch otomatik başlasın"**
kutusunu işaretleyip kaydet (Startup klasörüne bat dosyası ekler/siler; exe'den
çalışıyorsan exe'yi, jar'dan çalışıyorsan jar'ı başlatır).

## Modem protokolü

TP-Link GDPR şifreli web protokolünün tamamı (login akışı, RSA/AES detayları,
doğrulanmış istek/yanıt örnekleri): [docs/modem-api.md](docs/modem-api.md)

## Mimari

```
Scheduler (15 sn) → TpLinkCollector (şifreli /cgi_gdpr) → LineSample
                  → SampleRepository (SQLite)
                  → AlertEngine → ToastNotifier + TelegramNotifier (kuyruklu)
JavaFX UI (Pano / Grafikler / Olaylar / Ayarlar) ← canlı dinleyiciler + DB
```

Alert kuralları (Ayarlar'dan değiştirilebilir): hat koptu/geldi (süre bilgisiyle),
örnekleme başına CRC artışı ≥ 50, SNR < 6 dB (art arda 3 örnek), sync hızı değişimi
(retrain). CRC sayacı reset'i (reboot) delta negatifse yok sayılır. Aynı tip bildirim
15 dk içinde tekrarlanmaz (kopma/gelme hariç).
