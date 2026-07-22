const packageName = 'app.package.name';
const payloadPath = `/data/data/${packageName}/files/hermes-hook.js`;
const beforePayloadPath = `/data/data/${packageName}/files/before-hermes-hook.js`

try {
    const bf = new File(beforePayloadPath, 'w');
    bf.write(`
        //Buraya orijinal koddan önce çalışacak kodlar yazılacak.    
    `)

    const f = new File(payloadPath, 'w');
    f.write(`
        //Buraya orijinal koddan sonra çalışacak kodlar yazılacak.   
    `);
    f.close();
    console.log(`[+] JS Payload dosyası yazıldı: ${payloadPath}`);
} catch (e) {
    console.error(`[-] Payload dosyası yazılamadı: ${e}`);
}

if (Java.available) {
    Java.perform(() => {
        
        const hookJS = () => {

            try {
                const CatalystInstanceImpl = Java.use('com.facebook.react.bridge.CatalystInstanceImpl');
                CatalystInstanceImpl.loadScriptFromAssets.implementation = function(assetManager, assetURL, loadSynchronously) {

                    console.log(`[+] (Legacy) Öncül modülümüz enjekte ediliyor...`);
                    this.loadScriptFromFile(beforePayloadPath, beforePayloadPath, loadSynchronously);

                    console.log(`[+] (Legacy) Orijinal Bundle Yükleniyor: ${assetURL}`);
                    this.loadScriptFromAssets(assetManager, assetURL, loadSynchronously);
                    
                    console.log(`[+] (Legacy) Ardıl modülümüz enjekte ediliyor...`);
                    this.loadScriptFromFile(payloadPath, payloadPath, loadSynchronously);
                };
            } catch (e) {
                console.log('[-] Legacy API bulunmuyor.')
            }

            try {
                const ReactInstance = Java.use('com.facebook.react.runtime.ReactInstance');
                const JSBundleLoader = Java.use('com.facebook.react.bridge.JSBundleLoader');

                ReactInstance.loadJSBundle.implementation = function(bundleLoader) {

                    console.log(`[+] (Bridgeless) Öncül modülümüz enjekte ediliyor...`);
                    const beforeLoader = JSBundleLoader.createFileLoader(beforePayloadPath, beforePayloadPath, false);
                    this.loadJSBundle(beforeLoader);

                    console.log(`[+] (Bridgeless) Orijinal Bundle Yükleniyor...`);

                    this.loadJSBundle(bundleLoader);

                    console.log(`[+] (Bridgeless) Ardıl modülümüz enjekte ediliyor...`);

                    const customLoader = JSBundleLoader.createFileLoader(payloadPath, payloadPath, false);
                    this.loadJSBundle(customLoader);
                };
                console.log('[+] Bridgeless (ReactInstance) hooklandı. JS enjeksiyonu bekleniyor...');
            } catch (e) {
                console.log('[-] Bridgeless API hooklanamadı: ' + e);
            }
        };

        const waitForClass = (className, callback) => {
            const interval = setInterval(() => {
                try {
                    Java.use(className);
                    clearInterval(interval);
                    callback();
                } catch (e) { }
            }, 10);
        };

        waitForClass('com.facebook.soloader.SoLoader', () => {
            console.log('[+] SoLoader bulundu, tetiklenmesi bekleniyor...');
            const SoLoader = Java.use('com.facebook.soloader.SoLoader');
            
            const initOverloads = SoLoader.init.overloads;
            initOverloads.forEach(overload => {
                overload.implementation = function(...args) {
                    this.init(...args);
                    hookJS();
                };
            });
        });
    });
}

