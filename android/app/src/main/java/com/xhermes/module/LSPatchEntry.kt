package com.xhermes.module

import android.app.Application
import android.content.Context
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object LSPatchHookGuard {
    val isInHook = ThreadLocal.withInitial { false }
}

class LSPatchEntry : IXposedHookLoadPackage {
    private var applicationAttachHookInstalled = false
    private var applicationContextReady = false
    private var packageName: String? = null

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam?) {
        if (lpparam == null) return
        val pkg = lpparam.packageName

        // 1. If loading our own app, mark module as active via SharedPreferences
        if (pkg == "com.xhermes") {
            try {
                XposedHelpers.findAndHookMethod(
                    android.app.Application::class.java,
                    "attach",
                    android.content.Context::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val ctx = (param.thisObject as? android.content.Context)
                                    ?: (param.args[0] as? android.content.Context)
                                ctx?.let {
                                    val appCtx = it.applicationContext ?: it
                                    val prefs = appCtx.getSharedPreferences("com.xhermes_module_status", android.content.Context.MODE_PRIVATE)
                                    prefs.edit().putBoolean("module_active", true).putLong("last_active_ts", System.currentTimeMillis()).commit()
                                    XLog.i("Module active flag written to SharedPreferences (LSPatch).")
                                }
                            } catch (t: Throwable) {
                                XLog.e("Failed to write module active flag (LSPatch)", t)
                            }
                        }
                    }
                )
                XLog.i("Self-activation hook installed for com.xhermes (LSPatch).")
            } catch (t: Throwable) {
                XLog.e("Failed to install self-activation hook (LSPatch)", t)
            }
            return
        }

        // 2. Record hooked package marker
        try {
            val markerDir = java.io.File("/data/data/com.xhermes/files/hooked_packages")
            if (!markerDir.exists()) markerDir.mkdirs()
            val markerFile = java.io.File(markerDir, pkg)
            if (!markerFile.exists()) markerFile.writeText(System.currentTimeMillis().toString())
        } catch (_: Throwable) {}

        packageName = pkg
        installApplicationAttachHook(lpparam.classLoader)
    }

    @Synchronized
    private fun installApplicationAttachHook(classLoader: ClassLoader) {
        if (applicationAttachHookInstalled) return
        applicationAttachHookInstalled = true
        try {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "attach",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            var ctx: Context? = null
                            if (param.thisObject is Context) {
                                ctx = param.thisObject as Context
                            } else if (param.args != null && param.args.isNotEmpty() && param.args[0] is Context) {
                                ctx = param.args[0] as Context
                            }
                            ctx?.let { onApplicationContextReady(it) }
                        } catch (t: Throwable) {
                            XLog.e("Application.attach post-hook failed (LSPatch)", t)
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XLog.e("Could not hook Application.attach (LSPatch)", t)
        }
    }

    @Synchronized
    private fun onApplicationContextReady(context: Context) {
        if (applicationContextReady) return
        applicationContextReady = true
        val appContext = context.applicationContext ?: context
        val classLoader = appContext.classLoader

        // Load config from provider
        val enabled = ConfigManager.loadConfig(appContext, packageName ?: "", null)

        // Register this package as hooked via XHermes ContentProvider
        try {
            val uri = android.net.Uri.parse("content://com.xhermes.provider/register_hook")
            val values = android.content.ContentValues().apply {
                put("package_name", packageName ?: "")
            }
            appContext.contentResolver.insert(uri, values)
            XLog.i("Registered hooked package '${packageName}' via ContentProvider (LSPatch).")
        } catch (t: Throwable) {
            XLog.w("Could not register hook via ContentProvider in LSPatch", t)
        }

        if (!enabled) {
            XLog.i("XHermes", "Target app is not enabled in settings. Skipping hooks (LSPatch).")
            return
        }

        // Aggressive Hollowing: Setup pre-instantiation & ContentProvider hooks ONLY if hollowProcess is active
        if (ConfigManager.hollowProcess) {
            try {
                XLog.i("XHermes", "Hollow Process enabled - Installing pre-instantiation & ContentProvider hooks (LSPatch)...")
                setupActivityInstantiateHook(classLoader)
                hookContentProvidersForHollowing(classLoader)
            } catch (t: Throwable) {
                XLog.e("XHermes", "Failed to setup Hollow Process hooks (LSPatch)", t)
            }
        }

        // Initialize React Native hooks
        try {
            XLog.i("XHermes", "Initializing React Native hooks (LSPatch)...")
            hookReactNative(classLoader)
        } catch (t: Throwable) {
            XLog.e("XHermes", "Direct React Native hooks failed (LSPatch). Listening for SoLoader...", t)
            setupSoLoaderHook(classLoader)
        }

        // Initialize Application onCreate hook (ONLY for Hollow Shell)
        if (ConfigManager.hollowProcess) {
            try {
                XLog.i("XHermes", "Initializing Application onCreate hooks for Hollow Process (LSPatch)...")
                val targetAppClass = appContext.applicationInfo?.className ?: "android.app.Application"
                hookApplicationOnCreate(classLoader, targetAppClass)
            } catch (t: Throwable) {
                XLog.e("XHermes", "Application onCreate hook failed (LSPatch)", t)
            }
        }

        // Initialize Activity WebView hook
        try {
            XLog.i("XHermes", "Initializing Activity WebView hooks (LSPatch)...")
            hookActivityForWebView(classLoader)
        } catch (t: Throwable) {
            XLog.e("XHermes", "Activity WebView hook failed (LSPatch)", t)
        }
    }

    private fun setupActivityInstantiateHook(classLoader: ClassLoader) {
        try {
            val instrumentationClass = XposedHelpers.findClass("android.app.Instrumentation", classLoader)
            for (method in instrumentationClass.declaredMethods) {
                if (method.name == "newActivity") {
                    try {
                        XposedHelpers.findAndHookMethod(
                            instrumentationClass,
                            "newActivity",
                            *method.parameterTypes,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    val args = param.args
                                    if (args.size >= 2 && args[0] is ClassLoader && args[1] is String) {
                                        val cl = args[0] as ClassLoader
                                        val targetClassName = args[1] as String
                                        hookActivityHierarchyMethods(cl, targetClassName)
                                    }
                                }
                            }
                        )
                    } catch (_: Throwable) {}
                }
            }
            XLog.i("XHermes", "Instrumentation.newActivity pre-instantiation hooks installed (LSPatch).")
        } catch (t: Throwable) {
            XLog.w("XHermes", "Failed to hook Instrumentation.newActivity (LSPatch): " + t.message)
        }

        try {
            val appComponentFactoryClass = XposedHelpers.findClass("android.app.AppComponentFactory", classLoader)
            for (method in appComponentFactoryClass.declaredMethods) {
                if (method.name == "instantiateActivity") {
                    try {
                        XposedHelpers.findAndHookMethod(
                            appComponentFactoryClass,
                            "instantiateActivity",
                            *method.parameterTypes,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    val args = param.args
                                    if (args.size >= 2 && args[0] is ClassLoader && args[1] is String) {
                                        val cl = args[0] as ClassLoader
                                        val targetClassName = args[1] as String
                                        hookActivityHierarchyMethods(cl, targetClassName)
                                    }
                                }
                            }
                        )
                    } catch (_: Throwable) {}
                }
            }
            XLog.i("XHermes", "AppComponentFactory.instantiateActivity pre-instantiation hooks installed (LSPatch).")
        } catch (t: Throwable) {
            XLog.w("XHermes", "Failed to hook AppComponentFactory.instantiateActivity (LSPatch): " + t.message)
        }
    }

    private fun hookActivityHierarchyMethods(classLoader: ClassLoader, className: String) {
        try {
            var currentClass: Class<*>? = XposedHelpers.findClass(className, classLoader)
            while (currentClass != null && currentClass != Any::class.java && currentClass != android.app.Activity::class.java) {
                try {
                    for (m in currentClass.declaredMethods) {
                        val mName = m.name
                        if (Modifier.isStatic(m.modifiers) || mName == "<init>" || mName == "<clinit>") {
                            continue
                        }
                        if (mName == "createReactActivityDelegate" || mName == "getActivityDelegate" || mName == "getMainComponentName") {
                            try {
                                XposedHelpers.findAndHookMethod(
                                    currentClass,
                                    mName,
                                    *m.parameterTypes,
                                    object : XC_MethodHook() {
                                        override fun beforeHookedMethod(param: MethodHookParam) {
                                            if (ConfigManager.hollowProcess) {
                                                XLog.i("XHermes", "Bypassed ${currentClass?.name}.${mName} (LSPatch - Hollow Shell).")
                                                param.result = handleDelegateBypass(m, param.args, param.thisObject)
                                            }
                                        }
                                    }
                                )
                            } catch (_: Throwable) {}
                        } else {
                            try {
                                XposedHelpers.findAndHookMethod(
                                    currentClass,
                                    mName,
                                    *m.parameterTypes,
                                    object : XC_MethodHook() {
                                        override fun afterHookedMethod(param: MethodHookParam) {
                                            if (ConfigManager.hollowProcess && param.hasThrowable()) {
                                                XLog.w("XHermes", "Suppressed ${currentClass?.name}.${mName} exception in Hollow Mode (LSPatch): " + param.throwable?.message)
                                                param.throwable = null
                                                param.result = handleDelegateBypass(m, param.args, param.thisObject)
                                            }
                                        }
                                    }
                                )
                            } catch (_: Throwable) {}
                        }
                    }
                } catch (_: Throwable) {}
                currentClass = currentClass.superclass
            }
        } catch (_: Throwable) {}
    }

    private fun hookContentProvidersForHollowing(classLoader: ClassLoader) {
        try {
            val providerInfoClass = XposedHelpers.findClass("android.content.pm.ProviderInfo", classLoader)
            XposedHelpers.findAndHookMethod(
                "android.content.ContentProvider",
                classLoader,
                "attachInfo",
                Context::class.java,
                providerInfoClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (ConfigManager.hollowProcess) {
                            val cpObj = param.thisObject
                            val concreteClass = cpObj?.javaClass
                            val pkg = concreteClass?.`package`?.name ?: ""
                            if (!pkg.startsWith("com.xhermes") && concreteClass != null) {
                                try {
                                    XposedHelpers.findAndHookMethod(
                                        concreteClass,
                                        "onCreate",
                                        object : XC_MethodHook() {
                                            override fun beforeHookedMethod(innerParam: MethodHookParam) {
                                                XLog.i("Aggressive Hollowing - Bypassed ContentProvider.onCreate (${concreteClass.name} - LSPatch)")
                                                innerParam.result = true
                                            }
                                        }
                                    )
                                } catch (_: Throwable) {}
                            }
                        }
                    }
                }
            )
            XLog.i("ContentProvider hollowing hook installed successfully via attachInfo (LSPatch).")
        } catch (t: Throwable) {
            XLog.e("Failed to hook ContentProvider.attachInfo for hollowing (LSPatch)", t)
        }
    }

    private var activeWebViewRef: java.lang.ref.WeakReference<android.webkit.WebView>? = null

    private fun handleDelegateBypass(method: java.lang.reflect.Method, args: Array<out Any>?, thisObj: Any?): Any? {
        val methodName = method.name
        val returnType = method.returnType

        // Handle Back button key events for WebView navigation
        if ((methodName == "onKeyDown" || methodName == "onKeyUp") && args != null && args.isNotEmpty()) {
            val keyCode = args[0] as? Int ?: 0
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                if (methodName == "onKeyDown") {
                    val webView = activeWebViewRef?.get()
                    if (webView != null && webView.canGoBack()) {
                        webView.post { webView.goBack() }
                    } else {
                        val activity = (thisObj as? android.app.Activity)
                            ?: try {
                                thisObj?.javaClass?.getMethod("getActivity")?.invoke(thisObj) as? android.app.Activity
                            } catch (_: Throwable) { null }
                        activity?.finish()
                    }
                }
                return true
            }
        }

        if (methodName == "onBackPressed") {
            val webView = activeWebViewRef?.get()
            if (webView != null && webView.canGoBack()) {
                webView.post { webView.goBack() }
            } else {
                val activity = (thisObj as? android.app.Activity)
                    ?: try {
                        thisObj?.javaClass?.getMethod("getActivity")?.invoke(thisObj) as? android.app.Activity
                    } catch (_: Throwable) { null }
                activity?.finish()
            }
            return true
        }

        // Return primitive defaults for primitive types to avoid NullPointerException on unboxing
        if (returnType == java.lang.Boolean.TYPE || returnType == java.lang.Boolean::class.java) {
            return false
        }
        if (returnType == java.lang.Integer.TYPE || returnType == java.lang.Integer::class.java) {
            return 0
        }
        if (returnType == java.lang.Long.TYPE || returnType == java.lang.Long::class.java) {
            return 0L
        }
        if (returnType == java.lang.Float.TYPE || returnType == java.lang.Float::class.java) {
            return 0.0f
        }
        if (returnType == java.lang.Double.TYPE || returnType == java.lang.Double::class.java) {
            return 0.0
        }

        return null
    }

    private fun hookReactNative(classLoader: ClassLoader) {
        // Hook com.facebook.react.ReactActivity.createReactActivityDelegate to prevent custom delegate and TTI logger instantiation during Activity.<init>
        try {
            val reactActivityClass = XposedHelpers.findClass("com.facebook.react.ReactActivity", classLoader)
            val methods = reactActivityClass.declaredMethods
            for (method in methods) {
                if (method.name == "createReactActivityDelegate" || method.name == "getMainComponentName") {
                    try {
                        XposedHelpers.findAndHookMethod(
                            reactActivityClass,
                            method.name,
                            *method.parameterTypes,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    if (ConfigManager.hollowProcess) {
                                        XLog.i("Bypassing ReactActivity.${method.name} (LSPatch - Hollow Shell).")
                                        param.result = handleDelegateBypass(method, param.args, param.thisObject)
                                    }
                                }
                            }
                        )
                    } catch (t: Throwable) {
                        XLog.w("Failed to hook ReactActivity.${method.name} (LSPatch)", t)
                    }
                }
            }
            XLog.i("ReactActivity delegate creation hooks installed successfully (LSPatch).")
        } catch (t: Throwable) {
            XLog.w("Failed to hook ReactActivity class (LSPatch)", t)
        }

        // Hook all methods of ReactActivityDelegate to bypass React Native engine entirely when WebView injection is active
        try {
            val delegateClass = XposedHelpers.findClass("com.facebook.react.ReactActivityDelegate", classLoader)
            val methods = delegateClass.declaredMethods
            for (method in methods) {
                if (java.lang.reflect.Modifier.isAbstract(method.modifiers)) continue
                try {
                    XposedHelpers.findAndHookMethod(
                        delegateClass,
                        method.name,
                        *method.parameterTypes,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (ConfigManager.blockOriginalBundle && ConfigManager.injectWebView) {
                                    XLog.i("Bypassing ReactActivityDelegate.${method.name} to prevent React Native initialization (LSPatch).")
                                    param.result = handleDelegateBypass(method, param.args, param.thisObject)
                                }
                            }
                        }
                    )
                } catch (t: Throwable) {
                    XLog.w("Failed to hook ReactActivityDelegate method (LSPatch): ${method.name}", t)
                }
            }
            XLog.i("All ReactActivityDelegate methods hooked successfully (LSPatch).")
        } catch (t: Throwable) {
            XLog.e("Failed to hook ReactActivityDelegate class (LSPatch)", t)
        }

        // Hook expo.modules.ReactActivityDelegateWrapper for Expo-based apps (LSPatch)
        try {
            val expoDelegateClass = XposedHelpers.findClass("expo.modules.ReactActivityDelegateWrapper", classLoader)
            val methods = expoDelegateClass.declaredMethods
            for (method in methods) {
                if (java.lang.reflect.Modifier.isAbstract(method.modifiers)) continue
                try {
                    XposedHelpers.findAndHookMethod(
                        expoDelegateClass,
                        method.name,
                        *method.parameterTypes,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (ConfigManager.blockOriginalBundle && ConfigManager.injectWebView) {
                                    XLog.i("Bypassing ReactActivityDelegateWrapper.${method.name} to prevent React Native/Expo initialization (LSPatch).")
                                    param.result = handleDelegateBypass(method, param.args, param.thisObject)
                                }
                            }
                        }
                    )
                } catch (t: Throwable) {
                    XLog.w("Failed to hook ReactActivityDelegateWrapper method (LSPatch): ${method.name}", t)
                }
            }
            XLog.i("All ReactActivityDelegateWrapper methods hooked successfully (LSPatch).")
        } catch (t: Throwable) {
            XLog.w("Failed to hook ReactActivityDelegateWrapper class (Not an Expo app - LSPatch)")
        }

        // Hook ReactInstanceManager.createReactContextInBackground (LSPatch)
        try {
            XposedHelpers.findAndHookMethod(
                "com.facebook.react.ReactInstanceManager",
                classLoader,
                "createReactContextInBackground",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (ConfigManager.blockOriginalBundle) {
                            XLog.i("Blocking ReactInstanceManager.createReactContextInBackground (LSPatch) to prevent C++ crash.")
                            param.result = null
                        }
                    }
                }
            )
            XLog.i("ReactInstanceManager.createReactContextInBackground hooked successfully (LSPatch).")
        } catch (t: Throwable) {
            XLog.w("ReactInstanceManager hook failed (LSPatch): " + t.message)
        }

        // Hook ReactHost.start (LSPatch)
        try {
            XposedHelpers.findAndHookMethod(
                "com.facebook.react.runtime.ReactHost",
                classLoader,
                "start",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (ConfigManager.blockOriginalBundle) {
                            XLog.i("Blocking ReactHost.start (LSPatch) to prevent C++ crash.")
                            param.result = null
                        }
                    }
                }
            )
            XLog.i("ReactHost.start hooked successfully (LSPatch).")
        } catch (t: Throwable) {
            XLog.w("ReactHost hook failed (LSPatch): " + t.message)
        }

        var hookedAny = false

        // 1. Hook CatalystInstanceImpl.loadScriptFromAssets (Legacy)
        try {
            XposedHelpers.findAndHookMethod(
                "com.facebook.react.bridge.CatalystInstanceImpl",
                classLoader,
                "loadScriptFromAssets",
                android.content.res.AssetManager::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (LSPatchHookGuard.isInHook.get()) return
                        try {
                            LSPatchHookGuard.isInHook.set(true)
                            val thisObj = param.thisObject
                            val loadSynchronously = param.args[2] as Boolean
                            XLog.i("CatalystInstanceImpl.loadScriptFromAssets called. Executing pre-injection (LSPatch)...")
                            doPreInjection(thisObj, loadSynchronously)
                            if (ConfigManager.blockOriginalBundle) {
                                XLog.i("Blocking original bundle load (LSPatch).")
                                param.result = null
                            }
                        } finally {
                            LSPatchHookGuard.isInHook.set(false)
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (LSPatchHookGuard.isInHook.get()) return
                        try {
                            LSPatchHookGuard.isInHook.set(true)
                            val thisObj = param.thisObject
                            val loadSynchronously = param.args[2] as Boolean
                            XLog.i("CatalystInstanceImpl.loadScriptFromAssets completed. Executing post-injection (LSPatch)...")
                            doPostInjection(thisObj, loadSynchronously)
                        } finally {
                            LSPatchHookGuard.isInHook.set(false)
                        }
                    }
                }
            )
            XLog.i("Legacy API (CatalystInstanceImpl.loadScriptFromAssets) hooked successfully (LSPatch).")
            hookedAny = true
        } catch (t: Throwable) {
            XLog.i("CatalystInstanceImpl.loadScriptFromAssets hook failed (LSPatch): " + t.message)
        }

        // 2. Hook ReactInstance.loadJSBundle (Bridgeless)
        try {
            val jsBundleLoaderClass = XposedHelpers.findClass("com.facebook.react.bridge.JSBundleLoader", classLoader)
            XposedHelpers.findAndHookMethod(
                "com.facebook.react.runtime.ReactInstance",
                classLoader,
                "loadJSBundle",
                jsBundleLoaderClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (LSPatchHookGuard.isInHook.get()) return
                        try {
                            LSPatchHookGuard.isInHook.set(true)
                            val thisObj = param.thisObject
                            XLog.i("ReactInstance.loadJSBundle called. Executing pre-injection (LSPatch)...")
                            doPreInjectionBridgeless(thisObj, jsBundleLoaderClass)
                            if (ConfigManager.blockOriginalBundle) {
                                XLog.i("Blocking original bundle load (Bridgeless-LSPatch).")
                                param.result = null
                            }
                        } finally {
                            LSPatchHookGuard.isInHook.set(false)
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (LSPatchHookGuard.isInHook.get()) return
                        try {
                            LSPatchHookGuard.isInHook.set(true)
                            val thisObj = param.thisObject
                            XLog.i("ReactInstance.loadJSBundle completed. Executing post-injection (LSPatch)...")
                            doPostInjectionBridgeless(thisObj, jsBundleLoaderClass)
                        } finally {
                            LSPatchHookGuard.isInHook.set(false)
                        }
                    }
                }
            )
            XLog.i("Bridgeless API (ReactInstance.loadJSBundle) hooked successfully (LSPatch).")
            hookedAny = true
        } catch (t: Throwable) {
            XLog.i("ReactInstance.loadJSBundle hook failed (LSPatch): " + t.message)
        }

        if (!hookedAny) {
            XLog.w("Warning - No React Native loading APIs could be hooked (LSPatch).")
        }
    }

    private fun doPreInjection(catalystInstance: Any, loadSynchronously: Boolean) {
        if (ConfigManager.preScriptEnabled && ConfigManager.preScriptPath != null) {
            try {
                XLog.i("(Legacy) Loading pre-injection JS payload: " + ConfigManager.preScriptPath + " (LSPatch)")
                XposedHelpers.callMethod(catalystInstance, "loadScriptFromFile", ConfigManager.preScriptPath, ConfigManager.preScriptPath, loadSynchronously)
            } catch (t: Throwable) {
                XLog.e("Legacy pre-injection failed (LSPatch)", t)
            }
        }
    }

    private fun doPostInjection(catalystInstance: Any, loadSynchronously: Boolean) {
        if (ConfigManager.postScriptEnabled && ConfigManager.postScriptPath != null) {
            try {
                XLog.i("(Legacy) Loading post-injection JS payload: " + ConfigManager.postScriptPath + " (LSPatch)")
                XposedHelpers.callMethod(catalystInstance, "loadScriptFromFile", ConfigManager.postScriptPath, ConfigManager.postScriptPath, loadSynchronously)
            } catch (t: Throwable) {
                XLog.e("Legacy post-injection failed (LSPatch)", t)
            }
        }
    }

    private fun doPreInjectionBridgeless(reactInstance: Any, jsBundleLoaderClass: Class<*>) {
        if (ConfigManager.preScriptEnabled && ConfigManager.preScriptPath != null) {
            try {
                XLog.i("(Bridgeless) Loading pre-injection JS payload: " + ConfigManager.preScriptPath + " (LSPatch)")
                val beforeLoader = XposedHelpers.callStaticMethod(jsBundleLoaderClass, "createFileLoader", ConfigManager.preScriptPath, ConfigManager.preScriptPath, false)
                XposedHelpers.callMethod(reactInstance, "loadJSBundle", beforeLoader)
            } catch (t: Throwable) {
                XLog.e("Bridgeless pre-injection failed (LSPatch)", t)
            }
        }
    }

    private fun doPostInjectionBridgeless(reactInstance: Any, jsBundleLoaderClass: Class<*>) {
        if (ConfigManager.postScriptEnabled && ConfigManager.postScriptPath != null) {
            try {
                XLog.i("(Bridgeless) Loading post-injection JS payload: " + ConfigManager.postScriptPath + " (LSPatch)")
                val customLoader = XposedHelpers.callStaticMethod(jsBundleLoaderClass, "createFileLoader", ConfigManager.postScriptPath, ConfigManager.postScriptPath, false)
                XposedHelpers.callMethod(reactInstance, "loadJSBundle", customLoader)
            } catch (t: Throwable) {
                XLog.e("Bridgeless post-injection failed (LSPatch)", t)
            }
        }
    }

    private fun setupSoLoaderHook(classLoader: ClassLoader) {
        try {
            val soLoaderClass = XposedHelpers.findClass("com.facebook.soloader.SoLoader", classLoader)
            val methods = soLoaderClass.declaredMethods
            var hooked = false
            for (method in methods) {
                if (method.name == "init" && method.parameterTypes.isNotEmpty() && method.parameterTypes[0] == Context::class.java) {
                    XLog.i("Found SoLoader.init, hooking (LSPatch)...")
                    XposedHelpers.findAndHookMethod(soLoaderClass, "init", method.parameterTypes[0], object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            XLog.i("SoLoader.init called. Attempting to hook React Native (LSPatch)...")
                            hookReactNative(classLoader)
                        }
                    })
                    hooked = true
                }
            }
            if (hooked) {
                XLog.i("SoLoader init hooks registered successfully (LSPatch).")
            } else {
                XLog.i("SoLoader init methods not found in declaredMethods (LSPatch).")
            }
        } catch (t: Throwable) {
            XLog.e("SoLoader init hook setup failed (LSPatch)", t)
        }
    }

    private fun hookActivityForWebView(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Activity",
                classLoader,
                "attachBaseContext",
                android.content.Context::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // Let original attachBaseContext run but catch exceptions if in Hollow Mode
                    }
                }
            )
        } catch (_: Throwable) {}

        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Activity",
                classLoader,
                "onCreate",
                android.os.Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val isHollow = ConfigManager.hollowProcess || (ConfigManager.blockOriginalBundle && ConfigManager.injectWebView)
                        val activity = param.thisObject as android.app.Activity
                        if (isHollow && ConfigManager.injectWebView) {
                            activity.runOnUiThread {
                                try {
                                    XLog.i("Injecting custom WebView layout (LSPatch)...")
                                    if (ConfigManager.enableWebViewDebugging) {
                                        android.webkit.WebView.setWebContentsDebuggingEnabled(true)
                                        XLog.i("WebView debugging enabled (LSPatch).")
                                    }

                                    val webView = android.webkit.WebView(activity).apply {
                                        layoutParams = android.view.ViewGroup.LayoutParams(
                                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        settings.databaseEnabled = true
                                        webViewClient = object : android.webkit.WebViewClient() {
                                            override fun shouldOverrideUrlLoading(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                                                return false
                                            }
                                        }
                                        settings.setSupportZoom(false)
                                        settings.builtInZoomControls = false
                                        settings.displayZoomControls = false
                                        settings.useWideViewPort = false
                                        
                                        setOnTouchListener { _, event ->
                                            // Consume multi-touch events to block pinch-to-zoom
                                            event.pointerCount > 1
                                        }
                                        
                                        val url = ConfigManager.webViewUrl.trim()
                                        if (url.isNotEmpty()) {
                                            XLog.i("Loading WebView URL (LSPatch): $url")
                                            loadUrl(url)
                                        } else {
                                            val fallbackHtml = """
                                                <html>
                                                <body style='background:#1C1B1F;color:#E6E1E5;display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;margin:0;font-family:sans-serif;'>
                                                    <h2 style='color:#D0BCFF;'>XHermes WebView</h2>
                                                    <p style='color:#CAC4D0;'>Yönlendirilecek sayfa ayarlanmadı.</p>
                                                </body>
                                                </html>
                                            """.trimIndent()
                                            loadDataWithBaseURL("file:///android_asset/", fallbackHtml, "text/html", "UTF-8", null)
                                        }
                                    }
                                     activeWebViewRef = java.lang.ref.WeakReference(webView)
                                     activity.setContentView(webView)
                                     XLog.i("Layout successfully overridden with custom WebView (LSPatch).")
                                } catch (e: Exception) {
                                    XLog.e("Error putting WebView on screen (LSPatch)", e)
                                }
                            }
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XLog.e("Failed to hook Activity onCreate (LSPatch)", t)
        }
    }

    private fun hookApplicationOnCreate(classLoader: ClassLoader, targetAppClass: String) {
        try {
            XLog.i("Hooking target custom application class (LSPatch): $targetAppClass")
            XposedHelpers.findAndHookMethod(
                targetAppClass,
                classLoader,
                "onCreate",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (ConfigManager.blockOriginalBundle && ConfigManager.injectWebView) {
                            XLog.i("Custom Application.onCreate ($targetAppClass) bypassed (Hollow Shell - LSPatch).")
                            param.result = null
                        }
                    }
                }
            )
            XLog.i("Custom Application onCreate hooked successfully (LSPatch).")
        } catch (t: Throwable) {
            XLog.e("Failed to hook custom Application onCreate. Bypassing on base class (LSPatch)...", t)
            try {
                XposedHelpers.findAndHookMethod(
                    "android.app.Application",
                    classLoader,
                    "onCreate",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (ConfigManager.blockOriginalBundle && ConfigManager.injectWebView) {
                                XLog.i("Base Application.onCreate called and bypassed (LSPatch).")
                                param.result = null
                            }
                        }
                    }
                )
            } catch (ex: Throwable) {
                XLog.e("Base Application onCreate hook failed too (LSPatch)", ex)
            }
        }
    }
}
