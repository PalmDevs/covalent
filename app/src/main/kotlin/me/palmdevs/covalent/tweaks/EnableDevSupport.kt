package me.palmdevs.covalent.tweaks

import de.robv.android.xposed.XposedHelpers
import me.palmdevs.covalent.*
import me.palmdevs.covalent.api.Tweak
import me.palmdevs.covalent.api.reloadApp
import me.palmdevs.covalent.api.tweak
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

// @TODO: Find out where com.facebook.react.BuildConfig.UNSTABLE_ENABLE_FUSEBOX_RELEASE is used and hook that as well to fully enable dev mode in release builds?

private const val SHOULD_USE_DEV_SERVER_BUNDLE = false
        
/**
 * A tweak that enables React Native's dev support features in release builds.
 *
 * This is useful for development and debugging, but should be used with caution as it may have performance implications and could expose sensitive information in production environments.
 */
val enableDevSupport by tweak {
    apply {
        val clazz = classLoader.loadClass("com.facebook.react.defaults.DefaultReactHost")

        // @Target: The parameter list of getDefaultReactHost grows between versions, but useDevSupport is its only boolean.
        // RN 0.86: (context, packageList, jsMainModulePath, jsBundleAssetPath, jsBundleFilePath, jsRuntimeFactory,
        //           useDevSupport, cxxReactPackageProviders, exceptionHandler, bindingsInstaller)
        val getDefaultReactHost = clazz.declaredMethods.single {
            it.name == "getDefaultReactHost" && it.parameterTypes.count { type -> type == Boolean::class.javaPrimitiveType } == 1
        }
        val useDevSupportIndex = getDefaultReactHost.parameterTypes.indexOf(Boolean::class.javaPrimitiveType)

        getDefaultReactHost.hook {
            before {
                log.d("Original useDevSupport value: ${args[useDevSupportIndex]}")

                // Force useDevSupport to true to enable dev mode in release builds
                args[useDevSupportIndex] = true
            }
        }

        listOf(
            "com.facebook.react.devsupport.BridgeDevSupportManager",
            "com.facebook.react.devsupport.BridgelessDevSupportManager"
        )
            .mapNotNull { classLoader.loadClassOrNull(it) }
            .forEach { clazz -> hookDevSupportManager(clazz) }

        classLoader.loadClassOrNull("com.facebook.react.devsupport.DevSupportManagerBase")
            ?.let { hookPackagerStatus(it) }
    }
}

private fun hookDevSupportManager(clazz: Class<*>) {
    val handleReloadJSMethod = clazz.method("handleReloadJS")
    //val showDevOptionsDialogMethod = clazz.method("showDevOptionsDialog")

    // Relaunch the app instead of sending reload command to developer server
    handleReloadJSMethod.hook {
        before {
            reloadApp()
            result = null
        }
    }
}

/**
 * Only reports the packager as running if it can actually serve this app's bundle.
 *
 * With dev support forced on, React Native loads the bundle from any running Metro instead of the embedded one.
 * Metro can only serve bundles of the project it was started in, so other apps would fail with a 404.
 */
private fun Tweak.hookPackagerStatus(clazz: Class<*>) {
    // @Target: RN 0.86: ReactHostImpl only calls isPackagerRunning to choose between Metro and the embedded bundle
    val callbackClass = classLoader.loadClass("com.facebook.react.devsupport.interfaces.PackagerStatusCallback")

    clazz.method("isPackagerRunning", callbackClass).hook {
        before {
            val manager = thisObject
            val callback = args[0]

            args[0] = Proxy.newProxyInstance(classLoader, arrayOf(callbackClass)) { _, method, methodArgs ->
                val isRunning = methodArgs?.getOrNull(0)
                if (method.name != "onPackagerStatusFetched" || isRunning != true) {
                    return@newProxyInstance method.invoke(callback, *methodArgs.orEmpty())
                }

                thread(name = "CovalentPackagerProbe") {
                    method.invoke(callback, canServeBundle(manager!!))
                }
                null
            }
        }
    }
}

private fun Tweak.canServeBundle(manager: Any): Boolean = try {
    if (!SHOULD_USE_DEV_SERVER_BUNDLE) {
        log.i("Ignoring packager status check, loading embedded bundle")
        return false
    }
    
    val bundleName = XposedHelpers.callMethod(manager, "getJSAppBundleName") as String
    val devServerHelper = XposedHelpers.callMethod(manager, "getDevServerHelper")
    val url = XposedHelpers.callMethod(devServerHelper, "getDevServerBundleURL", bundleName) as String

    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "HEAD"
        connectTimeout = 5_000
        // A HEAD request builds the whole bundle before responding
        readTimeout = 120_000
    }

    val status = connection.responseCode
    connection.disconnect()

    (status == HttpURLConnection.HTTP_OK).also {
        if (it) log.i("Packager can serve $bundleName, loading bundle from packager")
        else log.i("Packager cannot serve $bundleName (HTTP $status), loading embedded bundle")
    }
} catch (e: Exception) {
    log.w("Failed to check if packager can serve bundle, loading embedded bundle", e)
    false
}
