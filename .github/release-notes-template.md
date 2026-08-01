<!--
  Release workflow'u bu dosyayı okur ve {{V}} yerine sürüm numarasını koyup
  GitHub Release açıklaması olarak kullanır. Serbestçe düzenlenebilir.
-->
## Kurulum

1. Aşağıdaki **LineWatch-{{V}}-windows-x64.zip** dosyasını indir.
2. Zip'i istediğin bir klasöre çıkar (Java kurulumu gerekmez, JDK paketin içinde).
3. `LineWatch.exe`'ye çift tıkla.

> [!NOTE]
> **"Windows bilgisayarınızı korudu" uyarısı alman normal.** Uygulama kod imzalama
> sertifikasıyla imzalanmadığı için SmartScreen ilk çalıştırmada uyarı verir.
> **Ek bilgi** → **Yine de çalıştır** ile devam edebilirsin. Dosyanın bozulmadığını
> `SHA256SUMS.txt` ile doğrulayabilirsin:
> `Get-FileHash .\LineWatch-{{V}}-windows-x64.zip -Algorithm SHA256`

## İlk çalıştırma

Uygulama açılır açılmaz internet kalitesini ölçmeye başlar — hiçbir ayar gerekmez.

DSL hat verilerini de görmek istiyorsan **Ayarlar → Modem izleme**'yi işaretle, modem
şifreni (modem web arayüzüne girdiğin şifre — Wi-Fi şifresi değil) yaz,
**Bağlantıyı test et**'e bas, ardından **Kaydet ve uygula**.

> [!WARNING]
> Modem 5 yanlış şifre denemesinden sonra 10 dakika kilitlenir. Şifreden emin
> değilsen önce tarayıcıdan modem arayüzüne girip doğrula.

Uygulama pencere kapatılınca kapanmaz, sistem tepsisine küçülür ve arka planda
izlemeye devam eder. Tamamen kapatmak için tepsi simgesine sağ tıkla → **Çıkış**.

## Uyumluluk

| Özellik | Gereksinim |
|---|---|
| İnternet izleme (gecikme, jitter, paket kaybı) | Yok — her modem, her bağlantı türü |
| DSL hat izleme (SNR, senkron, CRC) | TP-Link Archer VR400 (doğrulandı) |
| DSL izleme — muhtemelen çalışır | Aynı `/cgi_gdpr` protokolünü kullanan diğer TP-Link VDSL modemleri (test edilmedi) |
| İşletim sistemi | Windows 10 / 11 (x64) |

Modemin desteklenmiyorsa **Ayarlar → Modem izleme**'yi kapat; uygulama internet
kalitesi ölçümüyle çalışmaya devam eder.

Farklı bir modemde denediysen — çalışsın ya da çalışmasın — sonucu
[issue açarak](https://github.com/byFischer/LineWatch/issues) bildirebilirsin.
Desteklenen modem listesini genişletmenin en hızlı yolu bu.
