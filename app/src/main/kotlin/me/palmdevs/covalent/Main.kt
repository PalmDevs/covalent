package me.palmdevs.covalent

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage
import me.palmdevs.covalent.api.TweakBuilder
import me.palmdevs.covalent.api.TweakContext
import me.palmdevs.covalent.tweaks.*

private lateinit var modulePath: String

@Suppress("UNUSED")
class Main : IXposedHookLoadPackage, IXposedHookZygoteInit {
    @Volatile
    private var hooked = false

    // Do not naively change the order of these, as some of them depend on each other.
    private val tweaks = listOf<TweakBuilder>(
        covalentLifecycleSupport,
        covalentBridgeSupport,
        injectJSI,
        enableDevSupport,
        scriptLoader,
        example,
    )

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
    }

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (hooked) return

        val tweakContext = TweakBuilder.createContext(
            modulePath = modulePath,
            appInfo = param.appInfo,
            classLoader = param.classLoader,
            registerMethod = ::methodRegistrar,
            callJSMethod = ::jsCaller,
            withAppContext = ::withContext,
            withAppActivity = ::withActivity,
        )

        for (tweak in tweaks) tweak.build(tweakContext).apply()

        hooked = true
    }
}