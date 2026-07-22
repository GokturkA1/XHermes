# XHermes (v0.5.0)

**XHermes**, Android platformunda React Native (Hermes ve JSC JS motorları) tabanlı uygulamalara dinamik JavaScript betikleri enjekte etmek, özelleştirilmiş WebView arayüzleri yerleştirmek ve analitik/izleme süreçlerini agresif bir şekilde etkisiz hale getirmek için geliştirilmiş gelişmiş bir **Xposed / LSPosed & LSPatch** modülüdür.

---

## 🚀 Öne Çıkan Özellikler

- **Pre / Post JS Betik Enjeksiyonu**: Hedef uygulamanın React Native motoruna (Hem Legacy `CatalystInstanceImpl` hem de New Architecture Bridgeless `ReactInstance`) orijinal bundle çalışmadan önce (*Pre-script*) ve çalıştıktan sonra (*Post-script*) özel JavaScript kodları yükleme.
- **Orijinal Paket Engelleme (Block Original Bundle)**: Hedef uygulamanın kendi orijinal JavaScript bundle'ının çalıştırılmasını C++ seviyesinde çökme yaşanmadan engelleme.
- **Canlı WebView Arayüz Enjeksiyonu**: Uygulama açılışında orijinal React Native arayüzünü tamamen ezerek yerine canlı bir `WebView` (harici bir URL veya fallback HTML) yerleştirme.
- **Agresif İçi Boş Süreç (Hollow Process)**:
  - **ContentProvider Nötrleme**: `Sentry`, `Firebase`, `Adjust` vb. üçüncü taraf ContentProvider'ların `onCreate` metotlarını dinamik olarak baypas ederek başlangıç analitik yükünü sıfırlama.
  - **Application.onCreate Baypası**: Uygulamanın ağır başlatıcılarını devre dışı bırakarak içi boş bir kabuk (Hollow Shell) oluşturma.
  - **Ön-Örnekleme Hiyerarşik Kancalama**: `Instrumentation.newActivity` ve `AppComponentFactory.instantiateActivity` üzerinden Activity henüz örneklenmeden hiyerarşideki (`MainActivity` $\to$ `ReactActivity`) ezilmiş (overridden) metotları kancalama.
  - **Çok Seviyeli İstisna Bastırma**: `attachBaseContext`, `onCreate`, `onWindowFocusChanged` gibi yaşam döngüsü metotlarında sıfırlanmamış NotNull değişkenlerin neden olduğu çökmeleri otomatik yakalama ve yutma.
- **Otomatik React Native Tespiti**: `LibChecker-Rules-Bundle` entegrasyonu ile cihazda yüklü React Native uygulamalarını otomatik olarak tespit etme.
- **Merkezi XLog Loglama**: Tüm Logcat çıktılarını tek bir merkezden (`TAG = "XHermes"`) izleme (`logcat -s XHermes`).

---

## 📱 Desteklenen Uygulamalar ve Motorlar

XHermes, React Native mimarisini kullanan tüm Android uygulamalarıyla uyumludur:

- **Discord** (`com.discord`)
- **Wolvesville / Werewolf Online** (`com.werewolfapps.online`)
- **Expo Framework** tabanlı uygulamalar (`ReactActivityDelegateWrapper`)
- **React Native CLI** (Hermes veya JSC motoru kullanan tüm uygulamalar)

---

## 📋 Çalışma Şartları ve Gereksinimler

- **Android Sürümü**: Android 7.0 (API Level 24) ve üzeri (Önerilen: Android 9.0+ / API 28+).
- **Xposed Ortamı**:
  - **LSPosed** (Rootlu cihazlar / Zygisk ortamı)
  - **LSPatch** (Rootsuz cihazlar için APK yamalama çözümü)
- **Paket Kapsamı (Scope)**: Hedef uygulamanın LSPosed modül kapsama listesine eklenmiş olması gerekir.
- **Önemli Güvenlik Kuralı**: Sistem framework'ü (`android` paketi) **asla** modül kapsamına eklenmemelidir. Modülde `system_server` çökmesini ve soft bootloop'u engelleyen otomatik koruma mevcuttur.

---

## 🛠️ Kurulum ve Kullanım Rehberi

1. **Modülü Aktifleştirme**:
   - XHermes APK'sını cihazınıza yükleyin.
   - LSPosed uygulamasını açıp **XHermes** modülünü aktifleştirin.
   - Kancalamak istediğiniz hedef uygulamayı (örneğin Discord veya Wolvesville) modül kapsamına seçin.
   - Cihazı veya hedef uygulamayı yeniden başlatın.

2. **Arayüz Üzerinden Yapılandırma**:
   - **XHermes** uygulamasını açın.
   - Otomatik tespit edilen React Native uygulamaları listesinden hedef uygulamanızı seçin.
   - İstediğiniz özellikleri aktif edin:
     - 🟢 **Pre-Script / Post-Script**: Uygulamaya enjekte edilecek JS betiklerinizi girin.
     - 🛑 **Block Original Bundle**: Orijinal JS paketini engelleyin.
     - 🌐 **Inject WebView**: Web sayfası yönlendirmesini açın ve hedef URL'yi girin (Örn: `https://wolvesville.com`).
     - ⚡ **Hollow Process (Agresif İçi Boş Süreç)**: Ağır analitik ve başlatıcıları tamamen baypas etmek için bu seçeneği açın.
   - **Ayarları Kaydet** butonuna basın.

---

## 💻 Kaynak Koddan Derleme Rehberi

### Gereksinimler

- **Node.js**: `v22.11.0` veya üzeri
- **JDK**: OpenJDK 17 veya üzeri
- **Android SDK**: `API 36` (Android 16)
- **Android NDK**: `27.1.12297006`

### Derleme Adımları

1. **Bağımlılıkları Yükleyin**:
   ```bash
   npm install
   ```

2. **Android Proje Klasörüne Geçin ve Derleyin**:
   ```bash
   cd android
   
   # Debug APK derleme:
   .\gradlew.bat assembleDebug
   
   # Release APK derleme:
   .\gradlew.bat assembleRelease
   ```
   *Çıktı konumu: `android/app/build/outputs/apk/debug/app-debug.apk`*

---

## 📄 Lisans

Bu proje özel kullanım ve araştırma amaçlı geliştirilmiştir.
