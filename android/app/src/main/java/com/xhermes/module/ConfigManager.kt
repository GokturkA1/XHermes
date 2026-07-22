package com.xhermes.module

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import java.io.File

object ConfigManager {
    var preScript = ""
    var postScript = ""
    var preScriptEnabled = false
    var postScriptEnabled = false
    var blockOriginalBundle = false
    var injectWebView = false
    var enableWebViewDebugging = false
    var webViewUrl = ""
    var hollowProcess = false
    var preScriptPath: String? = null
    var postScriptPath: String? = null

    fun loadConfig(context: Context, packageName: String, moduleAppInfo: android.content.pm.ApplicationInfo?): Boolean {
        try {
            var configsJson: String? = null
            
            // Query ContentProvider IPC directly
            XLog.i("Querying ContentProvider IPC...")
            var moduleCtx: Context? = null
            if (moduleAppInfo != null) {
                try {
                    val method = context.javaClass.getMethod(
                        "createApplicationContext",
                        android.content.pm.ApplicationInfo::class.java,
                        Int::class.javaPrimitiveType
                    )
                    moduleCtx = method.invoke(context, moduleAppInfo, Context.CONTEXT_IGNORE_SECURITY) as Context
                    XLog.i("Successfully created module context from ApplicationInfo directly.")
                } catch (e: Throwable) {
                    XLog.w("createApplicationContext reflection failed: " + e.message)
                }
            }

            // Fallback to createPackageContext if direct instantiation fails
            if (moduleCtx == null) {
                try {
                    moduleCtx = context.createPackageContext("com.xhermes", Context.CONTEXT_IGNORE_SECURITY)
                } catch (ignored: Throwable) {}
            }

                // Query ContentProvider using the module context resolver or fallback context resolver
                try {
                    val resolver = moduleCtx?.contentResolver ?: context.contentResolver
                    val uri = Uri.parse("content://com.xhermes.provider/settings")
                    val cursor = resolver.query(uri, null, null, null, null)
                    if (cursor != null) {
                        cursor.use {
                            if (it.moveToFirst()) {
                                val idx = it.getColumnIndex("configs_json")
                                if (idx != -1) {
                                    configsJson = it.getString(idx)
                                    XLog.i("Retrieved configs via SettingsProvider: $configsJson")
                                }
                            }
                        }
                    } else {
                        XLog.w("SettingsProvider query returned null cursor.")
                    }
                } catch (e: Throwable) {
                    XLog.e("SettingsProvider query failed: " + e.message, e)
                }

            if (configsJson == null) {
                XLog.i("Unable to retrieve any configuration json via ContentProvider IPC.")
                return false
            }

            val jsonArray = JSONArray(configsJson)
            var matchedConfig: org.json.JSONObject? = null
            for (i in 0 until jsonArray.length()) {
                val config = jsonArray.getJSONObject(i)
                val target = config.optString("targetPackage", "").trim()
                val enabled = config.optBoolean("enabled", false)
                if (target.equals(packageName, ignoreCase = true)) {
                    if (enabled) {
                        matchedConfig = config
                    }
                    break
                }
            }

            if (matchedConfig == null) {
                XLog.i("No active config found for $packageName")
                return false
            }

            preScript = matchedConfig.optString("preScript", "")
            postScript = matchedConfig.optString("postScript", "")
            preScriptEnabled = matchedConfig.optBoolean("preScriptEnabled", true)
            postScriptEnabled = matchedConfig.optBoolean("postScriptEnabled", true)
            blockOriginalBundle = matchedConfig.optBoolean("blockOriginalBundle", false)
            injectWebView = matchedConfig.optBoolean("injectWebView", false)
            enableWebViewDebugging = matchedConfig.optBoolean("enableWebViewDebugging", false)
            webViewUrl = matchedConfig.optString("webViewUrl", "")
            hollowProcess = matchedConfig.optBoolean("hollowProcess", false)

            // Write scripts into the target application's private internal storage
            val filesDir = context.filesDir
            if (filesDir != null) {
                val beforeFile = File(filesDir, "before-hermes-hook.js")
                val afterFile = File(filesDir, "hermes-hook.js")

                if (preScriptEnabled && preScript.isNotEmpty()) {
                    beforeFile.writeText(preScript)
                    preScriptPath = beforeFile.absolutePath
                    XLog.i("Pre-script written to target app storage: $preScriptPath")
                } else {
                    preScriptPath = null
                    if (beforeFile.exists()) beforeFile.delete()
                }

                if (postScriptEnabled && postScript.isNotEmpty()) {
                    afterFile.writeText(postScript)
                    postScriptPath = afterFile.absolutePath
                    XLog.i("Post-script written to target app storage: $postScriptPath")
                } else {
                    postScriptPath = null
                    if (afterFile.exists()) afterFile.delete()
                }
                return true
            }
            return false
        } catch (t: Throwable) {
            XLog.e("Failed to load config", t)
            return false
        }
    }
}
