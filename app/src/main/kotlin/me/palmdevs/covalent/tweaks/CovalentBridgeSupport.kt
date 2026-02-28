package me.palmdevs.covalent.tweaks

import de.robv.android.xposed.XposedHelpers
import me.palmdevs.covalent.api.Log
import me.palmdevs.covalent.api.Tweak
import me.palmdevs.covalent.api.tweak
import me.palmdevs.covalent.hook
import me.palmdevs.covalent.method
import me.palmdevs.covalent.methodHook
import java.lang.ref.WeakReference
import java.lang.reflect.Method

private const val JS_CALLABLE_MODULE_NAME = "CovalentBridge"
private const val NATIVE_CALL_KEY = "covalent"
private const val NATIVE_CALL_METHOD_KEY = "method"
private const val NATIVE_CALL_ARGS_KEY = "args"

private lateinit var reactInstance: WeakReference<Any>
private lateinit var reactInstanceCallFunctionOnModule: Method

private lateinit var readableMapGetString: Method
private lateinit var readableMapToHashMap: Method
private lateinit var argumentsMakeNativeObject: Method

private val methods = mutableMapOf<String, (args: Map<String, Any?>) -> Any?>()
private lateinit var logger: Log

/**
 * A tweak that exposes a bridge for calling methods from JavaScript, and a bridge for calling JavaScript modules from Kotlin.
 *
 * @see setupJSToNativeBridge
 * @see setupNativeToJSBridge
 */
val covalentBridgeSupport by tweak {
    apply { _, classLoader ->
        logger = log

        // Tweaks should register their methods in the start block
        methods.clear()
        methods["covalent.test"] = { args ->
            log.i("Test method called")
            args
        }

        setupJSToNativeBridge(classLoader)
        setupNativeToJSBridge(classLoader)
    }
}

/**
 * # JS -> Native
 *
 * To call a native method, pass an object in the first argument that accepts it with the following structure to the hooked method:
 * ```js
 * {
 *   [NATIVE_CALL_KEY]: {
 *     method: string,
 *     args: Record<string, unknown>
 *   }
 * }
 * ```
 *
 * [NATIVE_CALL_KEY] is set above.
 *
 * The result will be a `Promise<object>` with either a `result` or `error` key:
 *
 * [Unit] return values will be converted to `null` when passing the result back to JS.
 *
 * ```js
 * {
 *   [NATIVE_CALL_KEY]: {
 *     result: unknown // The return value of the method
 *   }
 * }
 * ```
 *
 * or
 *
 * ```js
 * {
 *   [NATIVE_CALL_KEY]: {
 *     error: string
 *   }
 * }
 * ```
 *
 * Once again, [NATIVE_CALL_KEY] is set above.
 */
fun setupJSToNativeBridge(classLoader: ClassLoader) {
    val arguments = classLoader.loadClass("com.facebook.react.bridge.Arguments")
    val readableMap = classLoader.loadClass("com.facebook.react.bridge.ReadableMap")
    val promise = classLoader.loadClass("com.facebook.react.bridge.Promise")

    val promiseResolve = promise.method("resolve", Any::class.java)
    argumentsMakeNativeObject = arguments.method("makeNativeObject", Any::class.java)
    readableMapGetString = readableMap.method("getString", String::class.java)
    readableMapToHashMap = readableMap.method("toHashMap")

    classLoader.loadClass("com.facebook.react.modules.blob.FileReaderModule")
        .method("readAsDataURL", readableMap, promise).hook {
            before {
                val (callData, promise) = args
                callNativeMethod(callData!!).let {
                    promiseResolve.invoke(promise!!, mapOf(NATIVE_CALL_KEY to it).toNativeObject())
                    result = null
                }
            }
        }
}

/**
 * # Native -> JS
 *
 * Register a callable module from JS with the name defined in [JS_CALLABLE_MODULE_NAME].
 * The callable module method will be called like so:
 *
 * ```js
 * // methodName: String
 * // args: [args: Record<string, unknown>]
 * callableModule[methodName](args)
 * ```
 *
 * No return value or error handling is currently implemented for native -> JS calls.
 * To work around this, register your own custom native method specifically for handling responses from JS calls.
 */
private fun Tweak.setupNativeToJSBridge(classLoader: ClassLoader) {
    val reactInstanceClass = classLoader.loadClass("com.facebook.react.runtime.ReactInstance")
    val nativeArrayClass = classLoader.loadClass("com.facebook.react.bridge.NativeArray")

    reactInstanceCallFunctionOnModule = reactInstanceClass.method(
        "callFunctionOnModule",
        String::class.java,
        String::class.java,
        nativeArrayClass,
    )

    XposedHelpers.findAndHookConstructor(
        reactInstanceClass,
        "com.facebook.react.runtime.BridgelessReactContext",
        "com.facebook.react.runtime.ReactHostDelegate",
        "com.facebook.react.fabric.ComponentFactory",
        "com.facebook.react.devsupport.interfaces.DevSupportManager",
        "com.facebook.react.bridge.queue.QueueThreadExceptionHandler",
        Boolean::class.javaPrimitiveType,
        "com.facebook.react.runtime.ReactHostInspectorTarget",
        methodHook {
            after {
                log.i("Got ReactInstance")
                reactInstance = WeakReference(thisObject!!)
            }
        }.build(),
    )
}

@Suppress("UNCHECKED_CAST")
private fun callNativeMethod(rawCallData: Any): Map<String, Any?> = try {
    val callData = rawCallData.toHashMap()[NATIVE_CALL_KEY] as? Map<String, Any?>
        ?: throw Error("Invalid native call data")
    val name = callData[NATIVE_CALL_METHOD_KEY] as? String
        ?: throw Error("Invalid native call method name")
    val method = methods[name]
        ?: throw Error("Native method not registered: $name")
    val args = callData[NATIVE_CALL_ARGS_KEY] as? Map<String, Any?>
        ?: throw Error("Invalid native call args")

    val result = method(args).toNativeObject()
    mapOf("result" to result)
} catch (e: Throwable) {
    mapOf("error" to e.stackTraceToString())
}

fun methodRegistrar(name: String, handler: (args: Map<String, Any?>) -> Unit) {
    if (methods.containsKey(name)) {
        logger.w("Overriding existing native method for: $name")
    }

    methods[name] = handler
}

fun jsCaller(methodName: String, args: Map<String, Any?>) {
    // This will throw on the CPP thread, if there are any errors. We can't catch those.
    reactInstanceCallFunctionOnModule.invoke(
        reactInstance.get()!!,
        JS_CALLABLE_MODULE_NAME,
        methodName,
        args.toNativeObject(),
    )
}

@Suppress("UNCHECKED_CAST")
private fun Any.toHashMap() = readableMapToHashMap.invoke(this) as HashMap<String, Any?>

private fun Any?.toNativeObject(): Any? = argumentsMakeNativeObject.invoke(
    null,
    when (this) {
        Unit -> null
        else -> this
    }
)