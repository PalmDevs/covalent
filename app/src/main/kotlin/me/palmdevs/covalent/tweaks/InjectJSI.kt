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

                val runtimeExecutor = XposedHelpers.callMethod(reactInstance, "getUnbufferedRuntimeExecutor")
                if (runtimeExecutor != null) {
                    log.i("Got runtime executor: $runtimeExecutor")

                    try {
                        val destructor = XposedHelpers.getObjectField(runtimeExecutor, "mDestructor")
                        val nativePointer = XposedHelpers.getLongField(destructor, "mNativePointer")

                        log.i("Instance at 0x${nativePointer.toString(16)}")
                        JSIInjector.injectJSI(nativePointer)
                    } catch (e: Exception) {
                        log.e("Failed to steal native pointer:", e)
                    }
                }
            }
        }.build())
    }
}