package com.xhermes.module

import android.app.Application
import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import java.lang.reflect.Modifier
object HookGuard {
    val isInHook = ThreadLocal.withInitial { false }
}

class ModuleEntry : XposedModule() {
    private var packageName: String? = null
    private var applicationAttachHookInstalled = false
    private var applicationContextReady = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        XLog.i("Loaded in process=" + param.processName)
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        val pkg = param.packageName
        
        // Skip System Framework and our own app (module can't hook itself in LSPosed scope)
        if (pkg == "android" || pkg == "com.xhermes") {
            return
        }

        packageName = pkg
        installApplicationAttachHook()
    }

    @Synchronized
    private fun installApplicationAttachHook() {
        if (applicationAttachHookInstalled) return
        applicationAttachHookInstalled = true
        try {
            val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
            attach.isAccessible = true
            hook(attach)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val thiz = chain.thisObject
                        var ctx: Context? = if (thiz is Context) thiz else null
                        if (ctx == null && chain.args.isNotEmpty() && chain.args[0] is Context) {
                            ctx = chain.args[0] as Context
                        }
                        ctx?.let { onApplicationContextReady(it) }
                    } catch (t: Throwable) {
                        XLog.e("XHermes", "Application.attach post-hook failed", t)
                    }
                    result
                }
        } catch (t: Throwable) {
            XLog.e("XHermes", "Could not hook Application.attach", t)
        }
    }

    @Synchronized
    private fun onApplicationContextReady(context: Context) {
        if (applicationContextReady) return
        applicationContextReady = true
        val appContext = context.applicationContext ?: context
        val classLoader = appContext.classLoader

        // Load config from provider using module application info to bypass NameNotFoundException
        val enabled = ConfigManager.loadConfig(appContext, packageName ?: "", getModuleApplicationInfo())

        // Register this package as hooked via XHermes ContentProvider
        try {
            val uri = android.net.Uri.parse("content://com.xhermes.provider/register_hook")
            val values = android.content.ContentValues().apply {
                put("package_name", packageName ?: "")
            }
            appContext.contentResolver.insert(uri, values)
            XLog.i("Registered hooked package '${packageName}' via ContentProvider.")
        } catch (t: Throwable) {
            XLog.w("XHermes", "Could not register hook via ContentProvider (XHermes app may not be installed)", t)
        }

        if (!enabled) {
            XLog.i("Target app is not enabled in settings. Skipping hooks.")
            return
        }

        // Aggressive Hollowing: Setup pre-instantiation & ContentProvider hooks ONLY if hollowProcess is active
        if (ConfigManager.hollowProcess) {
            try {
                XLog.i("Hollow Process enabled - Installing pre-instantiation & ContentProvider hooks...")
                setupActivityInstantiateHook(classLoader)
                hookContentProvidersForHollowing(classLoader)
            } catch (t: Throwable) {
                XLog.e("XHermes", "Failed to setup Hollow Process hooks", t)
            }
        }

        // Initialize React Native hooks
        try {
            XLog.i("Initializing React Native hooks...")
            hookReactNative(classLoader)
        } catch (t: Throwable) {
            XLog.e("XHermes", "Direct React Native hooks failed. Listening for SoLoader...", t)
            setupSoLoaderHook(classLoader)
        }

        // Initialize Application onCreate hook (ONLY for Hollow Shell)
        if (ConfigManager.hollowProcess) {
            try {
                XLog.i("Initializing Application onCreate hooks for Hollow Process...")
                val targetAppClass = appContext.applicationInfo?.className ?: "android.app.Application"
                hookApplicationOnCreate(classLoader, targetAppClass)
            } catch (t: Throwable) {
                XLog.e("XHermes", "Application onCreate hook failed", t)
            }
        }

        // Initialize Activity WebView hook
        try {
            XLog.i("Initializing Activity WebView hooks...")
            hookActivityForWebView(classLoader)
        } catch (t: Throwable) {
            XLog.e("XHermes", "Activity WebView hook failed", t)
        }
    }

    private fun setupActivityInstantiateHook(classLoader: ClassLoader) {
        try {
            val instrumentationClass = classLoader.loadClass("android.app.Instrumentation")
            for (method in instrumentationClass.declaredMethods) {
                if (method.name == "newActivity") {
                    try {
                        hook(method).intercept { chain ->
                            val args = chain.args
                            if (args.size >= 2 && args[0] is ClassLoader && args[1] is String) {
                                val cl = args[0] as ClassLoader
                                val targetClassName = args[1] as String
                                hookActivityHierarchyMethods(cl, targetClassName)
                            }
                            chain.proceed()
                        }
                    } catch (_: Throwable) {}
                }
            }
            XLog.i("Instrumentation.newActivity pre-instantiation hooks installed.")
        } catch (t: Throwable) {
            XLog.w("XHermes", "Failed to hook Instrumentation.newActivity: " + t.message)
        }

        try {
            val appComponentFactoryClass = classLoader.loadClass("android.app.AppComponentFactory")
            for (method in appComponentFactoryClass.declaredMethods) {
                if (method.name == "instantiateActivity") {
                    try {
                        hook(method).intercept { chain ->
                            val args = chain.args
                            if (args.size >= 2 && args[0] is ClassLoader && args[1] is String) {
                                val cl = args[0] as ClassLoader
                                val targetClassName = args[1] as String
                                hookActivityHierarchyMethods(cl, targetClassName)
                            }
                            chain.proceed()
                        }
                    } catch (_: Throwable) {}
                }
            }
            XLog.i("AppComponentFactory.instantiateActivity pre-instantiation hooks installed.")
        } catch (t: Throwable) {
            XLog.w("XHermes", "Failed to hook AppComponentFactory.instantiateActivity: " + t.message)
        }
    }

    private fun hookActivityHierarchyMethods(classLoader: ClassLoader, className: String) {
        try {
            var currentClass: Class<*>? = classLoader.loadClass(className)
            while (currentClass != null && currentClass != Any::class.java && currentClass != android.app.Activity::class.java) {
                try {
                    for (m in currentClass.declaredMethods) {
                        val mName = m.name
                        if (Modifier.isStatic(m.modifiers) || mName == "<init>" || mName == "<clinit>") {
                            continue
                        }
                        if (mName == "createReactActivityDelegate" || mName == "getActivityDelegate" || mName == "getMainComponentName") {
                            try {
                                hook(m).intercept { chain ->
                                    if (ConfigManager.hollowProcess) {
                                        XLog.i("Bypassed ${currentClass?.name}.${mName} (Hollow Shell).")
                                        handleDelegateBypass(m, chain.args, chain.thisObject)
                                    } else {
                                        chain.proceed()
                                    }
                                }
                            } catch (_: Throwable) {}
                        } else {
                            try {
                                hook(m).intercept { chain ->
                                    try {
                                        chain.proceed()
                                    } catch (t: Throwable) {
                                        if (ConfigManager.hollowProcess) {
                                            XLog.w("XHermes", "Suppressed ${currentClass?.name}.${mName} exception in Hollow Mode: " + t.message)
                                            handleDelegateBypass(m, chain.args, chain.thisObject)
                                        } else {
                                            throw t
                                        }
                                    }
                                }
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
            val cpClass = classLoader.loadClass("android.content.ContentProvider")
            val providerInfoClass = classLoader.loadClass("android.content.pm.ProviderInfo")
            val attachInfoMethod = cpClass.getDeclaredMethod("attachInfo", Context::class.java, providerInfoClass)
            hook(attachInfoMethod).intercept { chain ->
                if (ConfigManager.hollowProcess) {
                    val cpObj = chain.thisObject
                    val concreteClass = cpObj?.javaClass
                    val pkg = concreteClass?.`package`?.name ?: ""
                    if (!pkg.startsWith("com.xhermes") && concreteClass != null) {
                        try {
                            val onCreateMethod = concreteClass.getDeclaredMethod("onCreate")
                            hook(onCreateMethod).intercept { _ ->
                                XLog.i("XHermes: Aggressive Hollowing - Bypassed ContentProvider.onCreate (${concreteClass.name})")
                                true
                            }
                        } catch (_: Throwable) {}
                    }
                }
                chain.proceed()
            }
            XLog.i("XHermes: ContentProvider hollowing hook installed successfully via attachInfo.")
        } catch (t: Throwable) {
            XLog.e("Failed to hook ContentProvider.attachInfo for hollowing", t)
        }
    }

    private var activeWebViewRef: java.lang.ref.WeakReference<android.webkit.WebView>? = null

    private fun handleDelegateBypass(method: java.lang.reflect.Method, argsList: List<Any?>?, thisObj: Any?): Any? {
        val methodName = method.name
        val returnType = method.returnType

        // Handle Back button key events for WebView navigation
        if ((methodName == "onKeyDown" || methodName == "onKeyUp") && !argsList.isNullOrEmpty()) {
            val keyCode = argsList[0] as? Int ?: 0
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
            val reactActivityClass = classLoader.loadClass("com.facebook.react.ReactActivity")
            val methods = reactActivityClass.declaredMethods
            for (method in methods) {
                if (method.name == "createReactActivityDelegate" || method.name == "getMainComponentName") {
                    try {
                        hook(method).intercept { chain ->
                            if (ConfigManager.hollowProcess) {
                                XLog.i("XHermes: Bypassing ReactActivity.${method.name} (Hollow Shell).")
                                handleDelegateBypass(method, chain.args, chain.thisObject)
                            } else {
                                chain.proceed()
                            }
                        }
                    } catch (t: Throwable) {
                        XLog.w("Failed to hook ReactActivity.${method.name}", t)
                    }
                }
            }
            XLog.i("XHermes: ReactActivity delegate creation hooks installed successfully.")
        } catch (t: Throwable) {
            XLog.w("Failed to hook ReactActivity class", t)
        }

        // Hook all methods of ReactActivityDelegate to bypass React Native engine entirely when WebView injection is active
        try {
            val delegateClass = classLoader.loadClass("com.facebook.react.ReactActivityDelegate")
            val methods = delegateClass.declaredMethods
            for (method in methods) {
                if (Modifier.isAbstract(method.modifiers)) continue
                try {
                    hook(method).intercept { chain ->
                        if (ConfigManager.blockOriginalBundle && ConfigManager.injectWebView) {
                            XLog.i("XHermes: Bypassing ReactActivityDelegate.${method.name} to prevent React Native initialization.")
                            handleDelegateBypass(method, chain.args, chain.thisObject)
                        } else {
                            chain.proceed()
                        }
                    }
                } catch (t: Throwable) {
                    XLog.w("Failed to hook ReactActivityDelegate method: ${method.name}", t)
                }
            }
            XLog.i("XHermes: All ReactActivityDelegate methods hooked successfully.")
        } catch (t: Throwable) {
            XLog.e("Failed to hook ReactActivityDelegate class", t)
        }

        // Hook expo.modules.ReactActivityDelegateWrapper for Expo-based apps
        try {
            val expoDelegateClass = classLoader.loadClass("expo.modules.ReactActivityDelegateWrapper")
            val methods = expoDelegateClass.declaredMethods
            for (method in methods) {
                if (Modifier.isAbstract(method.modifiers)) continue
                try {
                    hook(method).intercept { chain ->
                        if (ConfigManager.blockOriginalBundle && ConfigManager.injectWebView) {
                            XLog.i("XHermes: Bypassing ReactActivityDelegateWrapper.${method.name} to prevent React Native/Expo initialization.")
                            handleDelegateBypass(method, chain.args, chain.thisObject)
                        } else {
                            chain.proceed()
                        }
                    }
                } catch (t: Throwable) {
                    XLog.w("Failed to hook ReactActivityDelegateWrapper method: ${method.name}", t)
                }
            }
            XLog.i("XHermes: All ReactActivityDelegateWrapper methods hooked successfully.")
        } catch (t: Throwable) {
            XLog.w("Failed to hook ReactActivityDelegateWrapper class (Not an Expo app)")
        }

        // Hook ReactInstanceManager.createReactContextInBackground to prevent engine initialization if blocking is enabled
        try {
            val reactInstanceManagerClass = classLoader.loadClass("com.facebook.react.ReactInstanceManager")
            val createMethod = reactInstanceManagerClass.getDeclaredMethod("createReactContextInBackground")
            hook(createMethod).intercept { chain ->
                if (ConfigManager.blockOriginalBundle) {
                    XLog.i("XHermes: Blocking ReactInstanceManager.createReactContextInBackground initialization to prevent C++ crash.")
                    null
                } else {
                    chain.proceed()
                }
            }
            XLog.i("XHermes: ReactInstanceManager.createReactContextInBackground hooked successfully.")
        } catch (t: Throwable) {
            XLog.w("ReactInstanceManager hook failed: " + t.message)
        }

        // Hook ReactHost.start (Bridgeless)
        try {
            val reactHostClass = classLoader.loadClass("com.facebook.react.runtime.ReactHost")
            val startMethod = reactHostClass.getDeclaredMethod("start")
            hook(startMethod).intercept { chain ->
                if (ConfigManager.blockOriginalBundle) {
                    XLog.i("XHermes: Blocking ReactHost.start initialization to prevent C++ crash.")
                    null
                } else {
                    chain.proceed()
                }
            }
            XLog.i("XHermes: ReactHost.start hooked successfully.")
        } catch (t: Throwable) {
            XLog.w("ReactHost hook failed: " + t.message)
        }

        var hookedAny = false

        // 1. Hook CatalystInstanceImpl.loadScriptFromAssets (Legacy)
        try {
            val catalystClass = classLoader.loadClass("com.facebook.react.bridge.CatalystInstanceImpl")
            val loadMethod = catalystClass.getDeclaredMethod(
                "loadScriptFromAssets",
                android.content.res.AssetManager::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType
            )
            
            hook(loadMethod).intercept { chain ->
                if (HookGuard.isInHook.get()) {
                    return@intercept chain.proceed()
                }
                try {
                    HookGuard.isInHook.set(true)
                    val thisObj = chain.thisObject!!
                    val loadSynchronously = chain.args[2] as Boolean
                    XLog.i("XHermes: CatalystInstanceImpl.loadScriptFromAssets called. Executing pre-injection...")
                    doPreInjection(thisObj, loadSynchronously)
                    val result = if (ConfigManager.blockOriginalBundle) {
                        XLog.i("XHermes: Blocking original bundle load.")
                        null
                    } else {
                        chain.proceed()
                    }
                    XLog.i("XHermes: CatalystInstanceImpl.loadScriptFromAssets completed. Executing post-injection...")
                    doPostInjection(thisObj, loadSynchronously)
                    result
                } finally {
                    HookGuard.isInHook.set(false)
                }
            }
            XLog.i("XHermes: Legacy API (CatalystInstanceImpl.loadScriptFromAssets) hooked successfully.")
            hookedAny = true
        } catch (t: Throwable) {
            XLog.i("XHermes: CatalystInstanceImpl.loadScriptFromAssets hook failed: " + t.message)
        }

        // 2. Hook ReactInstance.loadJSBundle (Bridgeless)
        try {
            val reactInstanceClass = classLoader.loadClass("com.facebook.react.runtime.ReactInstance")
            val jsBundleLoaderClass = classLoader.loadClass("com.facebook.react.bridge.JSBundleLoader")
            val loadMethod = reactInstanceClass.getDeclaredMethod("loadJSBundle", jsBundleLoaderClass)
            
            hook(loadMethod).intercept { chain ->
                if (HookGuard.isInHook.get()) {
                    return@intercept chain.proceed()
                }
                try {
                    HookGuard.isInHook.set(true)
                    val thisObj = chain.thisObject!!
                    XLog.i("XHermes: ReactInstance.loadJSBundle called. Executing pre-injection...")
                    doPreInjectionBridgeless(thisObj, jsBundleLoaderClass)
                    val result = if (ConfigManager.blockOriginalBundle) {
                        XLog.i("XHermes: Blocking original bundle load (Bridgeless).")
                        null
                    } else {
                        chain.proceed()
                    }
                    XLog.i("XHermes: ReactInstance.loadJSBundle completed. Executing post-injection...")
                    doPostInjectionBridgeless(thisObj, jsBundleLoaderClass)
                    result
                } finally {
                    HookGuard.isInHook.set(false)
                }
            }
            XLog.i("XHermes: Bridgeless API (ReactInstance.loadJSBundle) hooked successfully.")
            hookedAny = true
        } catch (t: Throwable) {
            XLog.i("XHermes: ReactInstance.loadJSBundle hook failed: " + t.message)
        }

        if (!hookedAny) {
            XLog.w("Warning - No React Native loading APIs could be hooked.")
        }
    }

    private fun doPreInjection(catalystInstance: Any, loadSynchronously: Boolean) {
        if (ConfigManager.preScriptEnabled && ConfigManager.preScriptPath != null) {
            try {
                XLog.i("XHermes: (Legacy) Loading pre-injection JS payload: " + ConfigManager.preScriptPath)
                val fileLoadMethod = catalystInstance.javaClass.getDeclaredMethod(
                    "loadScriptFromFile",
                    String::class.java,
                    String::class.java,
                    Boolean::class.javaPrimitiveType
                )
                fileLoadMethod.invoke(catalystInstance, ConfigManager.preScriptPath, ConfigManager.preScriptPath, loadSynchronously)
            } catch (t: Throwable) {
                XLog.e("Legacy pre-injection failed", t)
            }
        }
    }

    private fun doPostInjection(catalystInstance: Any, loadSynchronously: Boolean) {
        if (ConfigManager.postScriptEnabled && ConfigManager.postScriptPath != null) {
            try {
                XLog.i("XHermes: (Legacy) Loading post-injection JS payload: " + ConfigManager.postScriptPath)
                val fileLoadMethod = catalystInstance.javaClass.getDeclaredMethod(
                    "loadScriptFromFile",
                    String::class.java,
                    String::class.java,
                    Boolean::class.javaPrimitiveType
                )
                fileLoadMethod.invoke(catalystInstance, ConfigManager.postScriptPath, ConfigManager.postScriptPath, loadSynchronously)
            } catch (t: Throwable) {
                XLog.e("Legacy post-injection failed", t)
            }
        }
    }

    private fun doPreInjectionBridgeless(reactInstance: Any, jsBundleLoaderClass: Class<*>) {
        if (ConfigManager.preScriptEnabled && ConfigManager.preScriptPath != null) {
            try {
                XLog.i("XHermes: (Bridgeless) Loading pre-injection JS payload: " + ConfigManager.preScriptPath)
                val createFileLoaderMethod = jsBundleLoaderClass.getDeclaredMethod(
                    "createFileLoader",
                    String::class.java,
                    String::class.java,
                    Boolean::class.javaPrimitiveType
                )
                val beforeLoader = createFileLoaderMethod.invoke(null, ConfigManager.preScriptPath, ConfigManager.preScriptPath, false)
                val loadMethod = reactInstance.javaClass.getDeclaredMethod("loadJSBundle", jsBundleLoaderClass)
                loadMethod.invoke(reactInstance, beforeLoader)
            } catch (t: Throwable) {
                XLog.e("Bridgeless pre-injection failed", t)
            }
        }
    }

    private fun doPostInjectionBridgeless(reactInstance: Any, jsBundleLoaderClass: Class<*>) {
        if (ConfigManager.postScriptEnabled && ConfigManager.postScriptPath != null) {
            try {
                XLog.i("XHermes: (Bridgeless) Loading post-injection JS payload: " + ConfigManager.postScriptPath)
                val createFileLoaderMethod = jsBundleLoaderClass.getDeclaredMethod(
                    "createFileLoader",
                    String::class.java,
                    String::class.java,
                    Boolean::class.javaPrimitiveType
                )
                val customLoader = createFileLoaderMethod.invoke(null, ConfigManager.postScriptPath, ConfigManager.postScriptPath, false)
                val loadMethod = reactInstance.javaClass.getDeclaredMethod("loadJSBundle", jsBundleLoaderClass)
                loadMethod.invoke(reactInstance, customLoader)
            } catch (t: Throwable) {
                XLog.e("Bridgeless post-injection failed", t)
            }
        }
    }

    private fun setupSoLoaderHook(classLoader: ClassLoader) {
        try {
            val soLoaderClass = classLoader.loadClass("com.facebook.soloader.SoLoader")
            val methods = soLoaderClass.declaredMethods
            var hooked = false
            for (method in methods) {
                if (method.name == "init" && method.parameterTypes.isNotEmpty() && method.parameterTypes[0] == Context::class.java) {
                    XLog.i("XHermes: Found SoLoader.init, hooking...")
                    hook(method).intercept { chain ->
                        val result = chain.proceed()
                        XLog.i("XHermes: SoLoader.init called. Attempting to hook React Native...")
                        hookReactNative(classLoader)
                        result
                    }
                    hooked = true
                }
            }
            if (hooked) {
                XLog.i("XHermes: SoLoader init hooks registered successfully.")
            } else {
                XLog.i("XHermes: SoLoader init methods not found in declaredMethods.")
            }
        } catch (t: Throwable) {
            XLog.e("SoLoader init hook setup failed", t)
        }
    }

    private fun hookActivityForWebView(classLoader: ClassLoader) {
        val activityClass = classLoader.loadClass("android.app.Activity")
        
        try {
            val attachBaseContextMethod = activityClass.getDeclaredMethod("attachBaseContext", Context::class.java)
            hook(attachBaseContextMethod).intercept { chain ->
                try {
                    chain.proceed()
                } catch (t: Throwable) {
                    if (ConfigManager.hollowProcess) {
                        XLog.w("Suppressed Activity.attachBaseContext exception in Hollow Mode: " + t.message)
                        null
                    } else {
                        throw t
                    }
                }
            }
        } catch (_: Throwable) {}

        val onCreateMethod = activityClass.getDeclaredMethod("onCreate", android.os.Bundle::class.java)
        
        hook(onCreateMethod).intercept { chain ->
            val isHollow = ConfigManager.hollowProcess || (ConfigManager.blockOriginalBundle && ConfigManager.injectWebView)
            val result = try {
                chain.proceed() // Let original onCreate run (satisfies super.onCreate requirement)
            } catch (t: Throwable) {
                if (isHollow) {
                    XLog.w("Suppressed Activity.onCreate exception in Hollow Mode: " + t.message)
                    null
                } else {
                    throw t
                }
            }
            val activity = chain.thisObject as android.app.Activity
            
            if (isHollow && ConfigManager.injectWebView) {
                try {
                    XLog.i("XHermes: Injecting custom WebView layout...")
                    if (ConfigManager.enableWebViewDebugging) {
                        android.webkit.WebView.setWebContentsDebuggingEnabled(true)
                        XLog.i("XHermes: WebView debugging enabled.")
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
                            XLog.i("XHermes: Loading WebView URL: $url")
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
                    XLog.i("Layout successfully overridden with custom WebView.")
                } catch (e: Exception) {
                    XLog.e("Error putting WebView on screen", e)
                }
            }
            result
        }
    }

    private fun hookApplicationOnCreate(classLoader: ClassLoader, targetAppClass: String) {
        try {
            XLog.i("Hooking target custom application class: $targetAppClass")
            val appClass = classLoader.loadClass(targetAppClass)
            val onCreateMethod = appClass.getDeclaredMethod("onCreate")
            hook(onCreateMethod).intercept { chain ->
                if (ConfigManager.hollowProcess) {
                    XLog.i("Custom Application.onCreate ($targetAppClass) bypassed (Hollow Shell).")
                    null
                } else {
                    chain.proceed()
                }
            }
            XLog.i("Custom Application onCreate hooked successfully.")
        } catch (t: Throwable) {
            XLog.e("Failed to hook custom Application onCreate. Bypassing on base class...", t)
            try {
                val appClass = classLoader.loadClass("android.app.Application")
                val onCreateMethod = appClass.getDeclaredMethod("onCreate")
                hook(onCreateMethod).intercept { chain ->
                    if (ConfigManager.hollowProcess) {
                        XLog.i("Base Application.onCreate called and bypassed.")
                        null
                    } else {
                        chain.proceed()
                    }
                }
            } catch (ex: Throwable) {
                XLog.e("Base Application onCreate hook failed too", ex)
            }
        }
    }

    private fun writeHookedPackageMarker(pkg: String) {
        try {
            val markerDir = java.io.File("/data/data/com.xhermes/files/hooked_packages")
            if (!markerDir.exists()) markerDir.mkdirs()
            val markerFile = java.io.File(markerDir, pkg)
            if (!markerFile.exists()) {
                markerFile.writeText(System.currentTimeMillis().toString())
                XLog.i("Hooked package marker written for $pkg")
            }
        } catch (t: Throwable) {
            XLog.w("Could not write hooked marker for $pkg", t)
        }
    }
}
