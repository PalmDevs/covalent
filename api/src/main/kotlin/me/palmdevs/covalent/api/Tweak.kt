package me.palmdevs.covalent.api

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo

internal typealias MethodRegistrar = (methodName: String, handler: (args: Map<String, Any?>) -> Unit) -> Unit
internal typealias JSCaller = (methodName: String, args: Map<String, Any?>) -> Unit
internal typealias ContextSubscriber = ((Context) -> Unit) -> Unit
internal typealias ActivitySubscriber = ((Activity) -> Unit) -> Unit
internal typealias TweakApplyFunction = Tweak.() -> Unit

/**
 * Represents a set of tasks that can be executed by the framework.
 */
class Tweak internal constructor(
    val name: String,
    private val apply: TweakApplyFunction,
    private val context: TweakContext,
) : TweakContext by context {
    val log by lazy { Log.namespace(name) }

    /**
     * The entry point for tasks.
     */
    fun apply() = apply(this)

    /**
     * Calls a JavaScript method with the specified name and arguments.
     *
     * Example:
     *
     * ```kotlin
     * "my.js.method"(mapOf("arg1" to "value1", "arg2" to 123))
     * ```
     *
     * No return values or errors are caught from JavaScript.
     * In order to get results back from JavaScript, you can register a native method and pass the result back through that method.
     *
     */
    operator fun String.invoke(args: Map<String, Any?>) {
        context.callJSMethod(this, args)
    }
}

interface TweakContext {
    /**
     * The path to the module file, which can be used for loading resources from the module.
     *
     * If the app is used as a module, this will be the path to the APK file of the app itself.
     * If the app has been patched using LSPatch, this will be the path to the extracted module file.
     */
    val modulePath: String
    val appInfo: ApplicationInfo
    val classLoader: ClassLoader
    /**
     * Registers a native method that can be called from JavaScript.
     */
    val registerMethod: MethodRegistrar
    val callJSMethod: JSCaller
    /**
     * Executes the given block with the target application's [Context] when it is available.
     */
    val withAppContext: ContextSubscriber
    /**
     * Executes the given block with the target application's [Activity] when it is available.
     */
    val withAppActivity: ActivitySubscriber
}