# BetterDo · Android

**Kaydırmak yerine, bir kelime öğren.**

Türkçe açıklamalarla İngilizce ve Almanca çalışmak için yerel Android uygulaması.
İsim şimdilik BetterDo; uygulama adı `app/src/main/res/values/strings.xml` içinden
değiştirilebilir. Kotlin + Jetpack Compose kullanır.

## Özellikler

- Uzaktan yayınlanan İngilizce/Almanca dersler: günlük kelime, IPA, anlam ve kullanım bağlamı.
- Her derste üç örnek, Türkçe çevirileri, mini diyalog ve iki quiz sorusu.
- Android TextToSpeech ile normal/yavaş kelime ve cümle telaffuzu.
- Öğrendim / yarın tekrar et; 1, 3, 7, 14, 30 ve 60 günlük tekrar aralıkları.
- Dil bazında ayrı kelime defteri, arama, günlük seri ve ilerleme.
- Dersler HTTPS üzerinden indirilir ve telefonda SQLite'ta saklanır. APK'ya kelime/örnek cümle gömülmez.
- İlk kullanımda internet gerekir; sonraki açılışlarda indirilen dersler çevrimdışı açılır.
- Geliştirme agent'ı için `AGENTS.md`; yeni ders taslakları için Python/Ollama aracı.

Günlük ders ilk açılışta seçilir, o gün sabit kalır; sonraki gün öğrenilmemiş ilk
kelimeye geçilir. Dersler bitince en erken tekrar tarihi olan kelime seçilir.
“Öğrendim” için iki quiz sorusu da doğru yanıtlanmalıdır. Aynı gün tekrarlı
dokunuşlar seri sayısını veya tekrar aralığını artırmaz. İlerleme yerel takvim
gününe göre hesaplanır; telefonun saatini değiştirmek takvimi etkiler.

## Dersler nasıl güncellenir?

İlk veri kaynağı bu deponun `content/lessons.json` kataloğudur. Uygulama onu
GitHub'ın HTTPS adresinden indirir; katalog Android kaynaklarından ayrıdır.
Bu aşamada uzakta PostgreSQL/API sunucusu yoktur. Telefonun **SQLite** veritabanı
son doğrulanmış katalog sürümünü ve tekrarlar için geçmiş dersleri saklar;
ilerleme dosyası mevcut kullanıcılar için korunur. Room henüz kullanılmıyor.

Uygulama her yeni açılışta, gün değişiminde ve altı saatten eski verilerle geri
dönüldüğünde güncelleme dener. Üstteki **Dersleri güncelle** düğmesiyle de
yenilenebilir. Ağ kesilmesi, hatalı JSON veya eksik ders alanları mevcut verinin
üzerine yazmaz. Derslerin kimlikleri kalıcıdır; bir ders yayından kaldırılsa da
önceden indirilmiş kopyası kelime defteri/tekrar için saklanır. Yeni ders seçimine
yalnızca yayındaki dersler katılır. O gün seçilmiş kelime gün içinde değişmez.

Agent taslağı onaylandıktan sonra:

```powershell
py -3 scripts/publish_lesson.py drafts/en-YENI-KELIME.json --reviewed
py -3 scripts/validate_content.py
git add content/lessons.json
git commit -m "Publish new language lesson"
git push origin main
```

**APK yeniden derlenmez.** Yalnızca içerik değişiklikleri `Lesson catalog checks`
iş akışını çalıştırır. GitHub'ın önbelleği nedeniyle yayın hemen görünmeyebilir;
biraz sonra uygulamadan yeniden güncelle. `content/` herkese açık ders içeriğidir;
kullanıcı ilerlemesi veya gizli anahtar içermez. Katalog geçerli olarak boş veya
tek dilli olabilir; henüz içerik olmayan dilde uygulama boş durum ekranı gösterir.

İleride bir veritabanı API'si veya başka bir HTTPS barındırma hizmeti aynı
`{ "version": 1, "lessons": [...] }` biçimini sunabilir. Kaynak adresi Gradle
özelliğiyle yapılandırılır; bu yalnızca **sunucu adresi değiştiğinde** derlenir:

```powershell
.\gradlew.bat assembleDebug -PcatalogUrl=https://example.com/api/lessons
```

İndirme boyutu en fazla 2 MB'dır. Üretim sürümünde yalnızca HTTPS kabul edilir.
Testler, dış ağa bağlı kalmadan yerel sahte HTTP sunucusu kullanır; test dersleri
yalnızca test APK'sına girer.

## Android Studio ile çalıştırma

1. Bu depoyu Android Studio'da aç.
2. Gradle JDK olarak **JDK 17** seç; SDK Manager'dan **Android SDK 35** yükle.
3. Gradle senkronizasyonunu tamamla.
4. Android 8.0 (API 26) veya üzeri telefon/emülatör seç ve Run'a bas.

Proje AGP 8.9.2, Gradle 8.11.1, Kotlin 2.1.20 ve Compose Compiler eklentisini
sabit sürümlerle kullanır. Sürüm gereksinimleri için
[Android Gradle Plugin belgesi](https://developer.android.com/build/releases/agp-8-9-0-release-notes)
ve [Compose kurulumu](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler).

Windows PowerShell:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Linux/macOS:

```sh
bash ./gradlew testDebugUnitTest lintDebug assembleDebug
```

SDK yolu Android Studio tarafından `local.properties` dosyasına yazılır veya
`ANDROID_HOME` ortam değişkeniyle verilebilir. Bu makineye özel dosya Git'e girmez.
APK: `app/build/outputs/apk/debug/app-debug.apk`.

## GitHub üzerinden APK

`main` dalına push, pull request veya elle başlatılan **Android checks and APK**
iş akışı içerik testlerini, Android birim testlerini, lint ve debug APK derlemesini
çalıştırır. Yalnızca `content/` değişmişse APK derlenmez. Ardından API 35
emülatöründe arayüz ve uzaktan içerik güncelleme testleri çalışır.

[Actions](https://github.com/burakotlu/BetterDo/actions) sayfasında başarılı çalışmayı
açıp **BetterDo-debug-apk** artifact'ını indir ve ZIP'ten APK'yı çıkar.
Bu geliştirme APK'sıdır; Play Store yayını değildir. Telefonda APK'yı açarken
Android'in ilgili kaynak için kurulum iznini vermen gerekebilir.

## Telaffuz ve kayıt

Telefonunda İngilizce/Almanca TTS ses paketi bulunmalı. Eksikse uygulama açıklama
gösterir; Android'in metin okuma ayarlarından ilgili dili yükleyebilirsin.
Çevrimdışı telaffuz, seçilen sistem ses motorunun çevrimdışı ses desteğine bağlıdır.
Uygulama mikrofon istemez ve konuşmanı kaydetmez. Dinleme sırasında uygulamadan
ayrılınca ses durur.

İlerleme uygulamanın özel depolama alanına atomik olarak yazılır. Uygulamayı
kaldırmak veya uygulama verisini temizlemek ilerlemeyi siler. Cihazlar arası
senkronizasyon yoktur; Android otomatik yedekleme bu ilk sürümde kapalıdır.
Okunamayan kayıtlar otomatik sıfırlanmaz veya üzerlerine yazılmaz.

## Projeyi geliştirecek agent

`AGENTS.md`, geliştirme agent'ının ürün kapsamını ve doğrulama adımlarını tanımlar.
Android arayüzü, dil bazında kayıt, erişilebilirlik ve ders biçimini korumasını ister.
Örnek görev: “AGENTS.md kurallarına göre günlük hatırlatma ekle; Android bildirim
iznini, kullanıcının seçtiği saati ve iptal etme akışını destekle; testleri çalıştır.”
Bu dosya kendi başına arka planda çalışan bir servis veya otomatik kod yazan bir
süreç başlatmaz.

## İçerik üreten agent

İsteğe bağlı Python aracı bilgisayardaki bir **Ollama** modeline bağlanır; telefonda
model çalıştırmaz. Uygulama bu araca ihtiyaç duymadan yayınlanan dersleri indirir.
Aracın protokolü [Ollama Chat API](https://docs.ollama.com/api/chat) ile uyumludur.

1. Ollama'yı kur, seçtiğin modeli indir ve yerel servisi çalıştır.
2. `ollama list` çıktısındaki model adını aşağıdaki `MODEL_ADI` yerine yaz.
3. Python 3 ile taslak üret:

```powershell
py -3 scripts/generate_lesson.py --language en --topic "at the bakery" --model MODEL_ADI
py -3 scripts/generate_lesson.py --language de --topic "at the train station" --model MODEL_ADI
```

Agent JSON üretir, formatı ve tekrar eden kelimeleri denetler, hatalı çıktıyı en
fazla üç denemede düzeltmeye çalışır ve `drafts/` dizinine yazar. Çıktı mevcut
dersin üzerine yazmaz. Bu denetim dilbilgisel doğruluğu kanıtlamaz: çevirileri,
IPA'yı, doğal kullanımı ve tek doğru quiz yanıtını gözden geçir.

```powershell
py -3 scripts/publish_lesson.py drafts/en-YENI-KELIME.json --reviewed
py -3 scripts/validate_content.py
py -3 -m unittest discover -s tests -v
```

Yeni dersler uzaktaki kataloğa yayınlanır; telefona ulaşmaları için yeniden APK
derlemek gerekmez. Otomatik günlük bulut üretimi, bildirim ve üyelik henüz yoktur.
Gerçek model üretimi için çalışan bir Ollama servisi gerekir.

## Testler ve yapı

- `app/src/test/`: günlük dersin sabit kalması, tekrar aralıkları, seri, tarih
  sınırları, JSON kayıt, iki dilin bağımsızlığı, güncelleme/çevrimdışı katalog ve hatalı indirmeler.
- `app/src/androidTest/`: dil değiştirme, quiz kilidi, ders tamamlama, Activity
  yeniden oluşturma, kelime defteri ve aynı APK ile yeni içerik indirme akışı.
  Gerçek cihazdaki ses kalitesini ölçmez.
- `tests/`: içerik biçimi, hatalı/tekrarlanan taslaklar, güvenli yayınlama ve sahte
  model yanıtlarıyla agent düzeltme döngüsü.
- `content/lessons.json`: uzaktan yayınlanan ders kataloğu; uygulama paketine girmez.
- `app/src/androidTest/assets/lessons.json`: yalnızca test senaryoları için sabit örnekler.
- `scripts/`: yalnızca Python standart kütüphanesini kullanan içerik araçları.

Yerel içerik testleri Python 3.7+ ile çalışır; güncel Python 3 önerilir. Makinede
`python` Python 2'ye işaret ediyorsa Windows'ta `py -3` kullan.

Hazırlayan: Burak Otlu.
