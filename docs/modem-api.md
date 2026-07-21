# TP-Link Archer VR400 — DSL İstatistik Protokolü (doğrulanmış)

Gerçek modemde (firmware tr_TR, GDPR şifreli arayüz) 2026-07-09'da canlı test edilip doğrulandı.

## Genel akış

1. **Parametre al:** `POST http://192.168.1.1/cgi/getParm?_=<ts>` (Referer: `http://192.168.1.1/` zorunlu)
   → yanıt JS değişkenleri: `ee` (RSA exponent hex), `nn` (RSA modulus hex, 512-bit), `seq` (int)
2. **Login:** `POST /cgi/login?data=<urlenc(aesData)>&sign=<rsaSign>&Action=1&LoginStatus=0`
   - `hash = MD5("admin" + şifre)` hex (lowercase)
   - AES anahtarı: `key` ve `iv` = 16 haneli rakam string (rastgele)
   - `aesData = Base64( AES-128-CBC-PKCS7( "admin\n" + şifre, key, iv ) )`
   - `sign = RSA_NoPad( "key=<key>&iv=<iv>&h=<hash>&s=<seq + aesData.length>" )`
   - Başarı: yanıt `$.ret=0;` + `JSESSIONID` cookie
3. **Token:** `GET /` (cookie ile) → sayfada `var token="<hex>";` regex ile alınır
4. **Veri isteği:** `POST /cgi_gdpr`
   - Header: `TokenID: <token>`, `Content-Type: text/plain`, Cookie, Referer
   - Body (literal, URL-encode YOK): `sign=<rsaSign>\r\ndata=<aesData>\r\n`
   - `aesData = Base64(AES(plaintextPayload))`, `sign = RSA_NoPad("h=<hash>&s=<seq + aesData.length>")`
   - Yanıt: AES ile şifreli (base64) → çözülünce aşağıdaki metin formatı

## RSA_NoPad detayı
Girdi string'i 64-byte'lık parçalara bölünür, her parça sıfır ile 64 byte'a doldurulur,
`BigInteger.modPow(e, n)` ile şifrelenir, hex'e çevrilir ve **128 hex karaktere sol-sıfır** ile
doldurulur; parçalar birleştirilir. (Padding YOK — flag=0 yolu.)

## Payload formatı (ACT protokolü)
```
<aksiyonKodları '&' ile>\r\n
[OID#stack#pstack]<bölümIndex>,<attrSayısı>\r\n
attr1\r\n
attr2\r\n
...
```
Aksiyon kodları: 1=GET, 2=SET, 5=GET_LIST, 7=OP, 8=CGI.

### Doğrulanmış DSL sorgusu
```
1&1\r\n
[WAN_DSL_INTF_CFG#1,0,0,0,0,0#0,0,0,0,0,0]0,12\r\n
status\r\nmodulationType\r\nupstreamCurrRate\r\ndownstreamCurrRate\r\n
downstreamNoiseMargin\r\nupstreamNoiseMargin\r\ndownstreamAttenuation\r\nupstreamAttenuation\r\n
downstreamPower\r\nupstreamPower\r\ndownstreamMaxRate\r\nupstreamMaxRate\r\n
[WAN_DSL_INTF_STATS_TOTAL#1,0,0,0,0,0#0,0,0,0,0,0]1,8\r\n
CRCErrors\r\nATUCCRCErrors\r\nFECErrors\r\nATUCFECErrors\r\n
HECErrors\r\nATUCHECErrors\r\nerroredSecs\r\nseverelyErroredSecs\r\n
```

### Örnek yanıt (canlı)
```
[1,0,0,0,0,0]0
status=Up
modulationType=VDSL2
upstreamCurrRate=7460          # kbps
downstreamCurrRate=24574       # kbps
downstreamNoiseMargin=117      # dB × 10 → 11.7 dB
upstreamNoiseMargin=81
downstreamAttenuation=174      # dB × 10 → 17.4 dB
upstreamAttenuation=44
downstreamPower=138            # dBm × 10
upstreamPower=67
downstreamMaxRate=39611        # kbps (hattın taşıyabileceği maksimum)
upstreamMaxRate=7461
[1,0,0,0,0,0]1
CRCErrors=429954               # kümülatif; delta ile takip edilir
ATUCCRCErrors=9                # upstream (santral tarafı)
FECErrors=0 ... erroredSecs=0
[error]0
```
`[error]0` → başarı. Yanıt sonundaki hata kodu 0 değilse istek reddedilmiştir.

### Logout (oturumu serbest bırakmak için)
Payload: `8\r\n[/cgi/logout#0,0,0,0,0,0#0,0,0,0,0,0]0,0\r\n`

## Notlar / tuzaklar
- Modem tek web oturumu tutar: kullanıcı tarayıcıdan arayüze girerse uygulamanın oturumu düşer
  → her `[error]!=0` veya HTTP hatasında **yeniden login** dene.
- 5 başarısız login → 10 dk kilit. Doğru şifreyle re-login sayaç artırmaz.
- SNR/attenuation/power değerleri ×10 tam sayı gelir.
- CRC sayaçları kümülatiftir; modem reboot/retrain'de sıfırlanabilir (delta negatifse reset say).
- Tüm isteklerde `Referer: http://192.168.1.1/` header'ı zorunlu (yoksa 403/500).
