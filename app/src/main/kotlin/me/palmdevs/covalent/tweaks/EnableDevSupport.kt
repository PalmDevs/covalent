package me.palmdevs.covalent.tweaks

import android.util.Log
import de.robv.android.xposed.XposedHelpers
import me.palmdevs.covalent.*
import me.palmdevs.covalent.api.reloadApp
import me.palmdevs.covalent.api.tweak

// @TODO: Find out where com.facebook.react.BuildConfig.UNSTABLE_ENABLE_FUSEBOX_RELEASE is used and hook that as well to fully enable dev mode in release builds?

/**
 * A tweak that enables React Native's dev support features in release builds.
 *
 * This is useful for development and debugging, but should be used with caution as it may have performance implications and could expose sensitive information in production environments.
 */
val enableDevSupport by tweak {
    apply { _, classLoader ->
        val clazz = classLoader.loadClass("com.facebook.react.defaults.DefaultReactHost")

        XposedHelpers.findAndHookMethod(
            clazz,
            "getDefaultReactHost",
            android.content.Context::class.java,           // context
            List::class.java,                              // packageList
            String::class.java,                            // jsMainModulePath
            String::class.java,                            // jsBundleAssetPath
            String::class.java,                            // jsBundleFilePath (Nullable String)
            "com.facebook.react.runtime.JSRuntimeFactory", // jsRuntimeFactory (Interface/Class name)
            Boolean::class.javaPrimitiveType,              // useDevSupport
            List::class.java,                              // cxxReactPackageProviders
            methodHook {
                before {
                    val originalValue = param.args[6] as Boolean
                    log.d("Original useDevSupport value: $originalValue")

                    // Force useDevSupport to true to enable dev mode in release builds
                    param.args[6] = true
                }
            }.build()
        )

        listOf(
            "com.facebook.react.devsupport.BridgeDevSupportManager",
            "com.facebook.react.devsupport.BridgelessDevSupportManager"
        )
            .mapNotNull { classLoader.loadClassOrNull(it) }
            .forEach { clazz -> hookDevSupportManager(clazz) }
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