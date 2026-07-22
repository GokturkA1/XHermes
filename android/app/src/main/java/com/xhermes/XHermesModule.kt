package com.xhermes

import android.content.Context
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.Promise

class XHermesModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
    override fun getName(): String = "XHermesModule"

    @ReactMethod
    fun saveConfigs(configsJson: String, promise: Promise) {
        try {
            val prefs = reactApplicationContext.getSharedPreferences("com.xhermes_preferences", Context.MODE_PRIVATE)
            prefs.edit().putString("configs_json", configsJson).commit()
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("SAVE_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun getConfigs(promise: Promise) {
        try {
            val prefs = reactApplicationContext.getSharedPreferences("com.xhermes_preferences", Context.MODE_PRIVATE)
            val configsJson = prefs.getString("configs_json", "[]") ?: "[]"
            promise.resolve(configsJson)
        } catch (e: Exception) {
            promise.reject("GET_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun getInstalledApps(promise: Promise) {
        try {
            val pm = reactApplicationContext.packageManager
            val apps = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
            val appList = org.json.JSONArray()

            for (app in apps) {
                val isSystem = (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                val label = pm.getApplicationLabel(app).toString()
                val pkgName = app.packageName
                val isReactNative = isReactNativeApp(app)

                val item = org.json.JSONObject().apply {
                    put("label", label)
                    put("packageName", pkgName)
                    put("isSystem", isSystem)
                    put("isReactNative", isReactNative)
                }
                appList.put(item)
            }
            promise.resolve(appList.toString())
        } catch (e: Exception) {
            promise.reject("GET_APPS_ERROR", e.message, e)
        }
    }

    /**
     * React Native detection compliant with LibChecker-Rules-Bundle (Absinthe/LibChecker-Rules-Bundle)
     */
    private fun isReactNativeApp(app: android.content.pm.ApplicationInfo): Boolean {
        // 1. Check native library directory against LibChecker RN library rules
        val nativeLibDir = app.nativeLibraryDir
        if (nativeLibDir != null) {
            val libDir = java.io.File(nativeLibDir)
            if (libDir.exists() && libDir.isDirectory) {
                val files = libDir.list()
                if (files != null) {
                    for (file in files) {
                        val fn = file.lowercase()
                        if (isLibCheckerRnNativeLib(fn)) {
                            return true
                        }
                    }
                }
            }
        }

        // 2. Check APK Zip entries against LibChecker RN asset rules (index.android.bundle, etc.)
        val apkPath = app.sourceDir
        if (apkPath != null) {
            try {
                java.util.zip.ZipFile(java.io.File(apkPath)).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entryName = entries.nextElement().name.lowercase()
                        // LibChecker Asset bundle rules
                        if (entryName == "assets/index.android.bundle" ||
                            entryName == "assets/index.bundle" ||
                            entryName == "assets/index.mobile.bundle" ||
                            entryName.contains("libreactnative") ||
                            entryName.contains("libhermes")) {
                            return true
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        return false
    }

    private fun isLibCheckerRnNativeLib(fileName: String): Boolean {
        // Official LibChecker native library rule tokens for React Native framework
        val rnTokens = arrayOf(
            "libreactnative", "libreact_native", "libreactnativejni", "libreactnativeblob",
            "libreactnativeorig", "libhermes", "libhermes-executor", "libhermes_executor",
            "libjsc", "libjscexecutor", "libyoga", "libjsi", "libfabricjni",
            "libturbomodulejsijni", "libreactnativeutils", "libreactnativejsi", "libfolly_json"
        )
        for (token in rnTokens) {
            if (fileName.contains(token)) return true
        }
        return false
    }

    @ReactMethod
    fun isModuleActive(promise: Promise) {
        try {
            // 1. System level detection (LSPosed, Zygisk, LSPlant, LSPosed Manager)
            if (isLSPosedEnvironmentDetected()) {
                promise.resolve(true)
                return
            }

            // 2. Persistent activation flag from ContentProvider registration
            val prefs = reactApplicationContext.getSharedPreferences("com.xhermes_hooked", Context.MODE_PRIVATE)
            val active = prefs.getBoolean("module_active", false)
            promise.resolve(active)
        } catch (e: Exception) {
            promise.resolve(false)
        }
    }

    @ReactMethod
    fun getHookedPackages(promise: Promise) {
        try {
            val prefs = reactApplicationContext.getSharedPreferences("com.xhermes_hooked", Context.MODE_PRIVATE)
            val packagesSet = prefs.getStringSet("hooked_packages", emptySet()) ?: emptySet()
            val now = System.currentTimeMillis()
            val result = org.json.JSONArray()
            for (pkg in packagesSet) {
                val ts = prefs.getLong("ts_$pkg", 0L)
                // Only return packages active in the last 24 hours
                if (now - ts < 24 * 60 * 60 * 1000L) {
                    result.put(pkg)
                }
            }
            promise.resolve(result.toString())
        } catch (e: Exception) {
            promise.resolve("[]")
        }
    }

    @ReactMethod
    fun clearHookedStatus(promise: Promise) {
        try {
            val prefs = reactApplicationContext.getSharedPreferences("com.xhermes_hooked", Context.MODE_PRIVATE)
            val wasActive = prefs.getBoolean("module_active", false)
            val isEnvActive = isLSPosedEnvironmentDetected() || wasActive

            // Remove hooked_packages set and individual timestamps, but preserve module_active state
            val editor = prefs.edit()
            editor.remove("hooked_packages")
            for (key in prefs.all.keys) {
                if (key.startsWith("ts_")) {
                    editor.remove(key)
                }
            }
            editor.putBoolean("module_active", isEnvActive)
            editor.commit()
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("CLEAR_ERROR", e.message, e)
        }
    }

    private fun isLSPosedEnvironmentDetected(): Boolean {
        try {
            // 1. Check common system properties set by LSPosed / Magisk / Zygisk / EdXposed
            val keys = arrayOf(
                "ro.lsposed.version", "persist.sys.lsposed.version", "lsp.version",
                "ro.zygisk.version", "persist.sys.zygisk.version",
                "vxp", "xposed.version"
            )
            for (k in keys) {
                if (!getSystemProperty(k).isNullOrEmpty()) return true
            }

            // 2. Check /proc/self/maps for loaded hook framework libraries
            val mapsFile = java.io.File("/proc/self/maps")
            if (mapsFile.exists()) {
                val lines = mapsFile.readLines()
                for (line in lines) {
                    val l = line.lowercase()
                    if (l.contains("liblsplant") || l.contains("liblspd") ||
                        l.contains("libzygisk") || l.contains("libriru") ||
                        l.contains("lsposed") || l.contains("edxposed") ||
                        l.contains("sandhook") || l.contains("pine")) {
                        return true
                    }
                }
            }

            // 3. Check LSPosed manager app presence
            val pm = reactApplicationContext.packageManager
            val lspPkgs = arrayOf("org.lsposed.manager", "org.meowcat.edxposed.manager", "de.robv.android.xposed.installer")
            for (p in lspPkgs) {
                try {
                    pm.getPackageInfo(p, 0)
                    return true
                } catch (_: Exception) {}
            }
        } catch (_: Throwable) {}
        return false
    }

    private fun getSystemProperty(key: String): String? {
        return try {
            val c = Class.forName("android.os.SystemProperties")
            val get = c.getMethod("get", String::class.java)
            val result = get.invoke(null, key) as? String
            if (result.isNullOrBlank()) null else result
        } catch (_: Throwable) {
            null
        }
    }
}
