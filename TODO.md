# XHermes - Gelecek Yol Haritası & TODO Listesi

Bu doküman, XHermes projesi için planlanan gelecek özellikler, geliştirmeler ve ekstrem deneysel modüllerin takibi amacıyla oluşturulmuştur.

---

## 🚀 Planlanan Geliştirmeler & TODO

### 1. 🌐 WebView Mimarisinin Tam Optimizasyonu
- **Tüm RN ve Yerel Uygulama Uyumluluğu**:
  - `ReactActivityDelegate` bağımlılıklarını izole eden akıllı mock/proxy katmanı.
  - Yaşam döngüsü (`onPause`, `onResume`, `onDestroy`, `onBackPressed`) metotlarının uygulama çökmelerine yol açmadan WebView context'ine aktarılması.
  - Donanım geri tuşu (`onBackPressed`) navigasyonunun WebView geçmişi (`canGoBack()`) ile tam entegrasyonu.

### 2. 📝 Uygulama İçi Kod Editörü (In-App Code Editor)
- **Monaco / CodeMirror Tabanlı Editör**:
  - Modül arayüzü (`com.xhermes`) içerisine entegre edilecek JS betik editörü.
  - Sözdizimi vurgulama (Syntax highlighting), otomatik tamamlama ve JS hata kontrolü.
  - Pre-script ve Post-script dosyalarını uygulama içerisinden doğrudan düzenleyip kaydedebilme.

### 3. 📦 Gelişmiş Betik Yönetimi (Script Management)
- **Yerel Betik Paketleme & Hermes Bytecode Derleyici (Local Bundling & Bytecode Compiler)**:
  - Düz `.js` betiklerini enjekte edilmeden önce otomatik olarak `pre.android.bundle` ve `post.android.bundle` halinde paketleme.
  - `hermesc` (Hermes Compiler) entegrasyonu ile betikleri doğrudan Hermes Bytecode (.hbc) formatına derleyerek çalışma zamanında yüksek performans sağlama.
- **Canlı Metro / Expo Dev-Server Entegrasyonu**:
  - Geliştirme esnasında yerel geliştirme sunucusundan (`http://127.0.0.1:8081` / Expo dev-server) canlı JS paketi çekebilme.
  - HMR (Hot Module Replacement) veya canlı kod yenileme (Live Reload) desteği.
- **Çoklu Betik Profili**:
  - Uygulamalara özel betik kütüphanesi ve aktif betikleri dinamik açıp kapama anahtarları.
  - Yerel depolama üzerindeki betiklerin versiyonlanması ve yedeklenmesi.

### 4. ⚙️ Arayüz ve Ayar Menüsü İyileştirmeleri
- Modernized UI/UX tasarımı.
- Hedef uygulama bazlı özel konfigürasyon profilleri oluşturma.
- Kolaylaştırılmış modül durum göstergeleri (LSPosed aktiflik/bağlantı durumu).

### 5. 💉 Frida Gadget Enjeksiyon Entegrasyonu
- **Dinamik Enjeksiyon Katmanı**:
  - Modül ayarlarından hedef uygulama için Frida Gadget aktifleştirme seçeneği.
  - Mimari otomatik tespiti (`arm64-v8a`, `armeabi-v7a` vb.) ve uygun `.so` kütüphanesini uygulama dizinine yerleştirip `System.load` ile yükleme.
  - Config yönetimi (Listen / Script / Wait-on-load modları arası kolay geçiş).

### 6. 🪵 Canlı Uygulama ve Modül Log Takibi (In-App Log Viewer)
- Modül arayüzü içerisinden Logcat akışını canlı izleme (`TAG = "XHermes"`).
- Filtrelenebilir log kategorileri (Info, Warning, Error, Frida Logs).
- Logları dosyaya aktarma (Export) özelliği.

---

## ⚡ EKSTREM DÜZEY DENEYSEL MODÜLLER

### 7. 🛠️ Harici `libhermestooling.so` Enjeksiyonu / Metro Debugger Kilidini Açma
- **Metro Debugger Unlocking**:
  - Release derlenmiş (production) React Native uygulamalarında kapatılmış olan C++ Hermes Debugger / Inspector yeteneklerini açma.
  - `libhermestooling.so` veya özel derlenmiş Hermes runtime kütüphanesini hedef sürece yerleştirerek Chrome DevTools / Flipper / Metro hattını production uygulamada aktif hale getirme denemeleri.
