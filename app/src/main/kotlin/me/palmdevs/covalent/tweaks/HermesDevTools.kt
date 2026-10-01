package me.palmdevs.covalent.tweaks

import android.app.AndroidAppHelper
import android.content.pm.ApplicationInfo
import android.os.Build
import android.provider.Settings
import de.robv.android.xposed.XposedHelpers
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.palmdevs.covalent.api.Log
import me.palmdevs.covalent.loadClassOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.time.Duration.Companion.seconds

/**
 * Provides React Native DevTools for apps with a fork of React Native, but stock Hermes.
 *
 * Since the debugger in the app's React Native libraries is compiled out, this drives the Hermes CDP agent directly,
 * and registers itself with Metro's inspector proxy the same way React Native does (`/inspector/device`).
 */
object HermesDevTools {
    init {
        System.loadLibrary("covalent")
    }

    private const val PAGE_ID = "1"

    // Must match the execution context ID the CDP agent is created with
    private const val EXECUTION_CONTEXT_ID = 1
    private val RECONNECT_DELAY = 2.seconds

    // Compiled from app/src/main/ts/devtools-console.ts
    private const val CONSOLE_SCRIPT_ASSET = "assets/devtools-console.bundle"

    private val log = Log.namespace("hermesDevTools")

    private lateinit var appInfo: ApplicationInfo
    private lateinit var serverHost: String
    private lateinit var metadata: JSONObject

    // CIO rather than OkHttp, as the app's OkHttp may be obfuscated
    private val client by lazy { HttpClient(CIO) { install(WebSockets) } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var session: WebSocketSession? = null

    fun install(
        executorPointer: Long,
        hermesPath: String,
        modulePath: String,
        appInfo: ApplicationInfo,
        classLoader: ClassLoader,
    ) {
        this.appInfo = appInfo
        serverHost = serverHost(classLoader)
        metadata = metadata(classLoader)

        val consoleScript = ZipFile(modulePath).use { it.getInputStream(it.getEntry(CONSOLE_SCRIPT_ASSET)).readBytes() }

        if (!nativeInstall(executorPointer, hermesPath, consoleScript)) {
            log.e("Failed to install, is the debugOptimized libhermesvm.so loaded?")
        }
    }

    /** Called from the JS thread once the CDP debug API is ready. */
    @JvmStatic
    private fun onReady() {
        log.i("Hermes CDP ready, connecting to inspector proxy at $serverHost")
        scope.launch { connectLoop() }
    }

    /** Called from arbitrary threads with CDP responses and events from Hermes. */
    @JvmStatic
    private fun onMessage(message: ByteArray) = sendToFrontend(String(message, Charsets.UTF_8))

    /** Called from the JS thread when a binding added with `Runtime.addBinding` is called. */
    @JvmStatic
    private fun onBindingCalled(name: ByteArray, payload: ByteArray) = sendToFrontend(
        JSONObject()
            .put("method", "Runtime.bindingCalled")
            .put(
                "params", JSONObject()
                    .put("name", String(name, Charsets.UTF_8))
                    .put("payload", String(payload, Charsets.UTF_8))
                    .put("executionContextId", EXECUTION_CONTEXT_ID)
            )
            .toString()
    )

    private suspend fun connectLoop() {
        val context = AndroidAppHelper.currentApplication()
        val androidId = context?.let { Settings.Secure.getString(it.contentResolver, Settings.Secure.ANDROID_ID) } ?: ""
        val deviceId = UUID.nameUUIDFromBytes("covalent-${appInfo.packageName}-$androidId".toByteArray()).toString()
        val deviceName = "${Build.MODEL} - ${Build.VERSION.RELEASE} - API ${Build.VERSION.SDK_INT}"

        val path = "/inspector/device?device=${deviceId.encode()}&name=${deviceName.encode()}" +
                "&app=${appInfo.packageName.encode()}&profiling=false"

        while (true) {
            try {
                client.webSocket("ws://$serverHost$path") {
                    session = this
                    log.i("Connected to inspector proxy")

                    for (frame in incoming) {
                        if (frame is Frame.Text) handleProxyMessage(JSONObject(frame.readText()))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // Retry silently, like React Native does, until Metro is running
            } catch (e: Throwable) {
                log.e("Inspector connection failed:", e)
            }

            session = null
            nativeDisconnect()
            delay(RECONNECT_DELAY)
        }
    }

    private fun handleProxyMessage(message: JSONObject) {
        val payload = message.optJSONObject("payload")

        when (message.getString("event")) {
            "getPages" -> send(
                JSONObject()
                    .put("event", "getPages")
                    .put("payload", JSONArray().put(page()))
                    .toString()
            )

            "connect" -> {
                log.i("DevTools connected")
                nativeConnect()
            }

            "disconnect" -> {
                log.i("DevTools disconnected")
                nativeDisconnect()
            }

            "wrappedEvent" -> handleFrontendMessage(payload!!.getString("wrappedEvent"))
        }
    }

    /** Handles the host-level methods that React Native's HostAgent and InstanceAgent would, and forwards the rest to Hermes. */
    private fun handleFrontendMessage(message: String) {
        val request = JSONObject(message)
        val id = request.opt("id")

        when (request.optString("method")) {
            // The Hermes CDP agent leaves announcing the execution context to the integrator.
            // Without it, the frontend has nothing to evaluate console input in or attribute console messages to.
            "Runtime.enable" -> {
                sendToFrontend(
                    JSONObject()
                        .put("method", "Runtime.executionContextCreated")
                        .put(
                            "params", JSONObject().put(
                                "context", JSONObject()
                                    .put("id", EXECUTION_CONTEXT_ID)
                                    .put("origin", "")
                                    .put("name", "main")
                            )
                        )
                        .toString()
                )
                nativeSend(message.toByteArray(Charsets.UTF_8))
            }

            // Used by the Components panel (__CHROME_DEVTOOLS_FRONTEND_BINDING__), among others. Like React Native, bindings are never removed from the runtime.
            "Runtime.addBinding" -> {
                nativeAddBinding(request.getJSONObject("params").getString("name").toByteArray(Charsets.UTF_8))
                sendResult(id)
            }

            "Runtime.removeBinding", "Log.enable", "Log.disable", "Overlay.setPausedInDebuggerMessage" -> sendResult(id)

            "ReactNativeApplication.enable" -> {
                sendResult(id)
                sendToFrontend(
                    JSONObject()
                        .put("method", "ReactNativeApplication.metadataUpdated")
                        .put("params", metadata)
                        .toString()
                )
            }

            "ReactNativeApplication.disable", "FuseboxClient.setClientMetadata" -> sendResult(id)

            else -> nativeSend(message.toByteArray(Charsets.UTF_8))
        }
    }

    private fun sendResult(id: Any?) =
        sendToFrontend(JSONObject().put("id", id).put("result", JSONObject()).toString())

    private fun sendToFrontend(message: String) {
        send(
            JSONObject()
                .put("event", "wrappedEvent")
                .put("payload", JSONObject().put("pageId", PAGE_ID).put("wrappedEvent", message))
                .toString()
        )
    }

    /** Queues [text] on the current connection, if any. Safe to call from any thread. */
    private fun send(text: String) {
        session?.outgoing?.trySend(Frame.Text(text))
    }

    private fun page() = JSONObject()
        .put("id", PAGE_ID)
        .put("title", "${metadata.optString("appDisplayName", appInfo.packageName)} (Covalent)")
        .put("app", appInfo.packageName)
        .put("description", "Hermes (Covalent)")
        .put(
            "capabilities", JSONObject()
                .put("nativePageReloads", true)
                .put("nativeSourceCodeFetching", false)
                .put("supportsMultipleDebuggers", false)
        )

    private fun metadata(classLoader: ClassLoader): JSONObject {
        val context = AndroidAppHelper.currentApplication()
        val version = classLoader.loadClassOrNull("com.facebook.react.modules.systeminfo.ReactNativeVersion")
            ?.let { XposedHelpers.getStaticObjectField(it, "VERSION") as Map<*, *> }
            ?.let { "${it["major"]}.${it["minor"]}.${it["patch"]}" }

        return JSONObject()
            .put("appDisplayName", context?.let { appInfo.loadLabel(it.packageManager).toString() } ?: appInfo.packageName)
            .put("appIdentifier", appInfo.packageName)
            .put("deviceName", Build.MODEL)
            .put("integrationName", "Covalent (Hermes)")
            .put("platform", "android")
            .apply { if (version != null) put("reactNativeVersion", version) }
    }

    private fun serverHost(classLoader: ClassLoader): String = runCatching {
        val helpers = classLoader.loadClass("com.facebook.react.modules.systeminfo.AndroidInfoHelpers")
        XposedHelpers.callStaticMethod(helpers, "getServerHost", AndroidAppHelper.currentApplication()) as String
    }.getOrDefault("localhost:8081")

    private fun String.encode(): String = URLEncoder.encode(this, "UTF-8")

    @JvmStatic
    private external fun nativeInstall(executorPointer: Long, hermesPath: String, consoleScript: ByteArray): Boolean

    @JvmStatic
    private external fun nativeConnect()

    @JvmStatic
    private external fun nativeDisconnect()

    @JvmStatic
    private external fun nativeSend(message: ByteArray)

    @JvmStatic
    private external fun nativeAddBinding(name: ByteArray)
}
