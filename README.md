# XHermes (v0.5.0)

**XHermes**, Android ortamında React Native (Hermes ve JSC) tabanlı uygulamalarda JavaScript bundle yüklemesine müdahale etmek, harici betikler çalıştırmak, orijinal JS paketini engellemek, ekrana alternatif WebView yerleştirmek ve süreç içi boşaltma (Hollow Process) denemeleri yapmak için geliştirilmiş bir **Xposed / LSPosed / LSPatch** modülüdür.

---

## 📌 Modül Özellikleri

- **JavaScript Betik Enjeksiyonu**:
  - **Pre-Script**: Orijinal JS paketi yüklenmeden önce belirtilen harici `.js` dosyasını çalıştırır.
  - **Post-Script**: Orijinal JS paketi yüklendikten sonra betiği çalıştırır.
  - Legacy (`CatalystInstanceImpl`) ve Bridgeless (`ReactInstance`) React Native mimarilerini kancalamayı dener.
- **Orijinal Paketi Engelleme (Block Original Bundle)**: Hedef uygulamanın kendi JavaScript bundle'ının yüklenmesini kancalar üzerinden iptal eder.
- **WebView Enjeksiyonu**: React Native Activity açılışında yerel arayüzü ezerek ekrana `android.webkit.WebView` nesnesi yerleştirir.
- **Hollow Process (İçi Boş Süreç) Modu**:
  - Uygulamanın `Application.onCreate` çağrısını ve tanımlı `ContentProvider` yapılarını (Sentry, analitik vb.) atlamayı amaçlar.
  - Activity yaşam döngüsü çağrılarındaki (`onCreate`, `onResume`, `onWindowFocusChanged` vb.) unhandled istisnaları bastırmaya çalışır.
- **React Native Uygulama Tespiti**: Cihazda yüklü paketleri tarayarak React Native kullanan uygulamaları listede gösterir.
- **XLog**: Logcat üzerinde log takibini `TAG = "XHermes"` etiketi altında toplar.

---

## ⚠️ Mevcut Kararsızlıklar ve Bilinen Sorunlar

XHermes deneysel bir hook modülüdür ve hedef uygulamanın React Native sürümüne ve mimarisine bağlı olarak aşağıdaki kararsızlıklar yaşanabilir:

1. **WebView Enjeksiyonu ve Yaşam Döngüsü Çökmeleri**:
   - WebView açıkken geri tuşuna (`onBackPressed`) basıldığında veya Activity arka plana alındığında (`onPause`/`onDestroy`), React Native'in kendi dahili delegatörünün (`ReactActivityDelegate`) başlatılmamış olmasından kaynaklı NullPointer istisnaları oluşabilir.
   - Hollow Process kapalıyken (Standart WebView Modu) bazı uygulamalar orijinal bundle'ı yüklemeye devam etmek isteyebilir.

2. **Hollow Process Yan Etkileri**:
   - `Application.onCreate` ve `ContentProvider` adımları baypas edildiğinde uygulamanın C++ veya Java tarafındaki yerel bağımlılıkları ilklendirilmez. Bu durumda enjekte edilen JS betiğinin çağırdığı Native Module'ler `undefined` dönebilir veya uygulamanın kapanmasına yol açabilir.

3. **React Native Sürüm Farklılıkları**:
   - Bridgeless (New Architecture) kullanan uygulamalar ile eski mimariyi (Bridge) kullanan uygulamalarda kancalanan C++/Java sınıfları farklıdır. Her uygulama sürümünde kancalar %100 başarıyla ilklendirilemeyebilir.

---

## 📱 Çalışabildiği Uygulamalar

React Native mimarisi (Hermes veya JSC motoru) kullanan uygulamalarda çalışması hedeflenmiştir:
- **Discord** (`com.discord`)
- **Wolvesville** (`com.werewolfapps.online`)
- Expo tabanlı React Native uygulamaları
- Genel React Native CLI uygulamaları

---

## 📋 Gereksinimler ve Çalışma Şartları

- **Android Sürümü**: Android 7.0 (API 24) ve üzeri.
- **Xposed Çerçevesi**:
  - **LSPosed** (Rootlu cihazlar)
  - **LSPatch** (Rootsuz cihazlar için APK yamalama)
- **Kapsam (Scope)**: Hedef uygulamanın LSPosed üzerinde modül kapsamına eklenmesi gereklidir.
- **Önemli**: `android` sistem paketi **asla** modül kapsamına eklenmemelidir (System Server çökmesini önlemek için).

---

## 🛠️ Kurulum ve Kullanım

1. **Modülü Aktifleştirin**: XHermes APK'sını yükleyin ve LSPosed / LSPatch üzerinden hedef uygulamayı kapsama ekleyip aktifleştirin.
2. **Ayarları Yapılandırın**: XHermes uygulamasını açın:
   - Hedef uygulamayı seçin.
   - Çalıştırılacak Pre/Post JS betik yollarını belirtin.
   - WebView kullanacaksanız **Inject WebView** seçeneğini açıp URL girin.
   - Başlatıcıları devreden çıkarmak istiyorsanız **Hollow Process** modunu seçin.
3. **Ayarları Kaydedin** ve hedef uygulamayı yeniden başlatın.

---

## 💻 Kaynak Koddan Derleme

### Gereksinimler
- Node.js `v22+`
- JDK 17+
- Android SDK (API 36) & NDK (`27.1.12297006`)

### Komutlar
```bash
npm install

cd android
.\gradlew.bat assembleDebug
```
*Derlenen APK konumu: `android/app/build/outputs/apk/debug/app-debug.apk`*
