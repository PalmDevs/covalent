package me.palmdevs.covalent.api

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import kotlin.reflect.KProperty

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