package me.palmdevs.covalent.tweaks

import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import me.palmdevs.covalent.api.tweak
import me.palmdevs.covalent.methodHook

object JSIInjector {
    init {
        System.loadLibrary("covalent")
    }

    @JvmStatic
    external fun injectJSI(nativePointer: Long)
}

/**
 * A tweak that hooks [com.facebook.react.runtime.ReactInstance] constructor to steal the JSI runtime native pointer and pass it to our native code.
 *
 * Currently, this sets a `__COVALENT__` global, but it can be modified to do more complex things.
 */
val injectJSI by tweak {
    apply {
        val reactInstanceClass = classLoader.loadClass("com.facebook.react.runtime.ReactInstance")

        XposedBridge.hookAllConstructors(reactInstanceClass, methodHook {
            after {
                val reactInstance = thisObject

                log.i("ReactInstance created")

                try {
                    val nativePointer = runtimeExecutorPointer(reactInstance!!)

                    log.i("Instance at 0x${nativePointer.toString(16)}")
                    JSIInjector.injectJSI(nativePointer)
                } catch (e: Exception) {
                    log.e("Failed to steal native pointer:", e)
                }
            }
        }.build())
    }
}

/**
 * Gets the native pointer of the RuntimeExecutor of a [com.facebook.react.runtime.ReactInstance],
 * which native code can read as a `JRuntimeExecutor` to run code on the JS thread.
 */
// @Target: This may change between versions
internal fun runtimeExecutorPointer(reactInstance: Any): Long {
    val runtimeExecutor = XposedHelpers.callMethod(reactInstance, "getUnbufferedRuntimeExecutor")
    val destructor = XposedHelpers.getObjectField(runtimeExecutor, "mDestructor")
    return XposedHelpers.getLongField(destructor, "mNativePointer")
}
