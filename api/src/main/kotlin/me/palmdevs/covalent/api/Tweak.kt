package me.palmdevs.covalent.api

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo

internal typealias MethodRegistrar = (methodName: String, handler: (args: Map<String, Any?>) -> Unit) -> Unit
internal typealias JSCaller = (methodName: String, args: Map<String, Any?>) -> Unit
internal typealias ContextSubscriber = ((Context) -> Unit) -> Unit
internal typealias ActivitySubscriber = ((Activity) -> Unit) -> Unit

/**
 * Represents a set of tasks that can be executed by the framework.
 */
class Tweak internal constructor(
    val name: String,
    private val apply: Tweak.(ApplicationInfo, ClassLoader) -> Unit,
    private val registerNativeMethod: MethodRegistrar,
    private val callJSMethod: JSCaller,
    internal val withContext: ContextSubscriber,
    internal val withActivity: ActivitySubscriber,
) {
    val log by lazy { Log.namespace(name) }

    /**
     * Executes the given block with the target application's [Context] when it is available.
     */
    fun withAppContext(block: (Context) -> Unit) = withContext(block)

    /**
     * Executes the given block with the target application's [Activity] when it is available.
     */
    fun withAppActivity(block: (Activity) -> Unit) = withActivity(block)

    /**
     * The entry point for tasks.
     */
    fun apply(applicationInfo: ApplicationInfo, classLoader: ClassLoader): Unit = apply(this, applicationInfo, classLoader)

    /**
     * Registers a native method that can be called from JavaScript.
     */
    fun registerMethod(name: String, handler: (args: Map<String, Any?>) -> Unit) {
        registerNativeMethod(name, handler)
    }

    /**
     * Calls a JavaScript method with the specified name and arguments.
     *
     * No return values or errors are caught from JavaScript.
     * In order to get results back from JavaScript, you can register a native method and pass the result back through that method.
     */
    operator fun String.invoke(args: Map<String, Any?>) {
        callJSMethod(this, args)
    }
}