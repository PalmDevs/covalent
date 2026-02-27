package me.palmdevs.covalent.tweaks

import android.content.res.AssetManager
import android.content.res.XModuleResources
import de.robv.android.xposed.XposedBridge
import me.palmdevs.covalent.*
import me.palmdevs.covalent.api.Tweak
import me.palmdevs.covalent.api.tweak
import java.lang.reflect.Method

lateinit var resources: XModuleResources

/**
 * A tweak that hooks React Native's script loading methods to load custom scripts and bundles.
 *
 * You can configure [scriptAssets] to set which scripts to load when React Native loads a bundle. These should be Hermes bytecode bundles.
 */
val scriptLoader by tweak {
    apply { _, classLoader ->
        listOf(
            $$"com.facebook.react.runtime.ReactInstance$loadJSBundle$1",
            // In some older React Native versions, the lambda class is named differently. Hook both to be safe.
            "com.facebook.react.runtime.ReactInstance$1",
            // TODO: On Bridgeless, hooking this will have no effect.
            "com.facebook.react.bridge.CatalystInstanceImpl"
        ).mapNotNull { classLoader.loadClassOrNull(it) }.forEach { hookLoader(it) }
    }
}

/**
 * Hooks `loadScriptFromAssets` and `loadScriptFromFile` to run custom scripts when React Native loads a bundle.
 */
private fun Tweak.hookLoader(instance: Class<*>) {
    try {
        val loadScriptFromAssets = instance.method(
            "loadScriptFromAssets",
            AssetManager::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType
        )

        loadScriptFromAssets.hook {
            before {
                log.d("Received call to loadScriptFromAssets: ${args[1]} (sync: ${args[2]})")
                runCustomScripts(this@hookLoader, loadScriptFromAssets)
            }
        }

        instance.method("loadScriptFromFile", String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
            .hook {
                before {
                    log.d("Received call to loadScriptFromFile: ${args[0]} (sync: ${args[2]})")
                    runCustomScripts(this@hookLoader, loadScriptFromAssets)
                }
            }
    } catch (e: Exception) {
        log.e("Failed to hook script loading methods in ${instance.name}:", e)
    }
}

private fun HookScope.runCustomScripts(tweak: Tweak, loadScriptFromAssets: Method) {
    val loadSynchronously = args[2]

    try {
        if (!::resources.isInitialized) resources = XModuleResources.createInstance(modulePath, null)

        for (asset in scriptAssets) {
            XposedBridge.invokeOriginalMethod(
                loadScriptFromAssets,
                thisObject,
                arrayOf(resources.assets, asset, loadSynchronously)
            )
        }
    } catch (e: Throwable) {
        tweak.log.e("Unable to run scripts:", e)
    }
}