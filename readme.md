# BetterDo · Android

**Kaydırmak yerine, bir kelime öğren.**

Türkçe açıklamalarla İngilizce ve Almanca çalışmak için yerel Android uygulaması.
İsim şimdilik BetterDo; uygulama adı `app/src/main/res/values/strings.xml` içinden
değiştirilebilir. Kotlin + Jetpack Compose kullanır.

## İlk sürüm

- Her dilde 7 başlangıç dersi: günlük bir kelime, IPA, anlam ve kullanım bağlamı.
- Her derste üç örnek, Türkçe çevirileri, mini diyalog ve iki quiz sorusu.
- Android TextToSpeech ile normal/yavaş kelime ve cümle telaffuzu.
- Öğrendim / yarın tekrar et; 1, 3, 7, 14, 30 ve 60 günlük tekrar aralıkları.
- Dil bazında ayrı kelime defteri, arama, günlük seri ve ilerleme.
- Dersler uygulamayla gelir. Hesap veya uygulama sunucusu gerekmez.
- Geliştirme agent'ı için `AGENTS.md`; yeni ders taslakları için Python/Ollama aracı.

Günlük ders ilk açılışta seçilir, o gün sabit kalır; sonraki gün öğrenilmemiş ilk
kelimeye geçilir. Dersler bitince en erken tekrar tarihi olan kelime seçilir.
“Öğrendim” için iki quiz sorusu da doğru yanıtlanmalıdır. Aynı gün tekrarlı
dokunuşlar seri sayısını veya tekrar aralığını artırmaz. İlerleme yerel takvim
gününe göre hesaplanır; telefonun saatini değiştirmek takvimi etkiler.

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
çalıştırır. Ardından API 35 emülatöründe arayüz testi çalışır.

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
model çalıştırmaz. Uygulama bu araca ihtiyaç duymadan hazır dersleri açar.
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

Yeni dersler APK'ya gömülür; telefona ulaşmaları için yeniden derleme ve güncelleme
gerekir. Otomatik günlük bulut üretimi, bildirim, üyelik ve uzaktan ders indirme
bu ilk sürümde yoktur. Gerçek model üretimi için çalışan bir Ollama servisi gerekir.

## Testler ve yapı

- `app/src/test/`: günlük dersin sabit kalması, tekrar aralıkları, seri, tarih
  sınırları, JSON kayıt ve iki dilin bağımsızlığı.
- `app/src/androidTest/`: dil değiştirme, quiz kilidi, ders tamamlama, Activity
  yeniden oluşturma ve kelime defteri akışı. Gerçek cihazdaki ses kalitesini ölçmez.
- `tests/`: içerik biçimi, hatalı/tekrarlanan taslaklar, güvenli yayınlama ve sahte
  model yanıtlarıyla agent düzeltme döngüsü.
- `app/src/main/assets/lessons.json`: ders kataloğu.
- `scripts/`: yalnızca Python standart kütüphanesini kullanan içerik araçları.

Yerel içerik testleri Python 3.7+ ile çalışır; güncel Python 3 önerilir. Makinede
`python` Python 2'ye işaret ediyorsa Windows'ta `py -3` kullan.

Hazırlayan: Burak Otlu.
