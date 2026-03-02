package me.palmdevs.covalent.api

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import kotlin.reflect.KProperty

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
     * "my.js.method"(
     *   "arg1" to "value1",
     *   "arg2" to 123,
     * )
     * ```
     *
     * No return values or errors are caught from JavaScript.
     * In order to get results back from JavaScript, you can register a native method and pass the result back through that method.
     *
     */
    operator fun String.invoke(vararg pairs: Pair<String, Any?>) = this(mapOf(pairs = pairs))

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
     */
    operator fun String.invoke(args: Map<String, Any?>) {
        callJSMethod(this, args)
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

/**
 * Provides delegation for creating and configuring tweaks.
 *
 * You are likely doing something wrong if you are getting errors with this interface.
 */
interface TweakDelegate {
    operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): TweakBuilder
}

/**
 * A builder for creating and configuring tweaks.
 */
class TweakBuilder private constructor() : TweakDelegate {
    companion object {
        internal fun create(block: TweakBuilder.() -> Unit): TweakDelegate {
            return TweakBuilder().apply(block)
        }

        fun createContext(
            modulePath: String,
            appInfo: ApplicationInfo,
            classLoader: ClassLoader,
            registerMethod: MethodRegistrar,
            callJSMethod: JSCaller,
            withAppContext: ContextSubscriber,
            withAppActivity: ActivitySubscriber,
        ) = object : TweakContext {
            override val modulePath: String = modulePath
            override val appInfo: ApplicationInfo = appInfo
            override val classLoader: ClassLoader = classLoader
            override val registerMethod: MethodRegistrar = registerMethod
            override val callJSMethod: JSCaller = callJSMethod
            override val withAppContext: ContextSubscriber = withAppContext
            override val withAppActivity: ActivitySubscriber = withAppActivity
        }
    }

    private var name: String? = null
    private var apply: TweakApplyFunction? = null


    /**
     * Sets the action to be performed when the tweak is executed.
     */
    fun apply(apply: TweakApplyFunction) {
        this@TweakBuilder.apply = apply
    }

    override operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): TweakBuilder {
        name = property.name
        return this
    }

    operator fun getValue(thisRef: Any?, property: KProperty<*>): TweakBuilder {
        return this
    }

    /**
     * Builds an [Tweak] instance using the configured properties and the provided dependencies.
     *
     * If you are a developer, you do not need to call this method directly.
     */
    fun build(context: TweakContext): Tweak {
        return name?.let { name ->
            apply?.let { apply ->
                Tweak(name, apply, context)
            } ?: throw Exception("Tweak does not apply anything")
        } ?: throw Exception("Tweak name has not been set")
    }
}

/**
 * Creates a [Tweak] using the provided configuration block.
 *
 * Example:
 *
 * ```kotlin
 * val myTweak by tweak {
 *   apply { appInfo, classLoader ->
 *       // Your tweak code here
 *   }
 * }
 * ```
 */
fun tweak(block: TweakBuilder.() -> Unit): TweakDelegate = TweakBuilder.create(block)