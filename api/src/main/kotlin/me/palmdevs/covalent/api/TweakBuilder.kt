package me.palmdevs.covalent.api

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
    }

    private var name: String? = null
    private var apply: (Tweak.(ApplicationInfo, ClassLoader) -> Unit)? = null


    /**
     * Sets the action to be performed when the tweak is executed.
     */
    fun apply(apply: Tweak.(ApplicationInfo, ClassLoader) -> Unit) {
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
    fun build(
        registerNativeMethod: MethodRegistrar,
        callJSMethod: JSCaller,
        withContext: ContextSubscriber,
        withActivity: ActivitySubscriber
    ): Tweak {
        return name?.let { name ->
            apply?.let { apply ->
                Tweak(
                    name = name,
                    apply = apply,
                    callJSMethod = callJSMethod,
                    registerNativeMethod = registerNativeMethod,
                    withContext = withContext,
                    withActivity = withActivity
                )
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