package me.palmdevs.covalent.tweaks

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import me.palmdevs.covalent.api.tweak

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
    apply { _, classLoader ->
        val reactInstanceClass = XposedHelpers.findClass("com.facebook.react.runtime.ReactInstance", classLoader)

        XposedBridge.hookAllConstructors(reactInstanceClass, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val reactInstance = param.thisObject

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
        })
    }
}