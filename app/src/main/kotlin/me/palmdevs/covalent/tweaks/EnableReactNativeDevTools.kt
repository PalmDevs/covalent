package me.palmdevs.covalent.tweaks

import android.content.Context
import android.content.res.AssetManager
import android.os.Build
import android.os.Process
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import me.palmdevs.covalent.api.Tweak
import me.palmdevs.covalent.api.tweak
import me.palmdevs.covalent.hook
import me.palmdevs.covalent.loadClassOrNull
import me.palmdevs.covalent.method
import me.palmdevs.covalent.methodHook
import java.io.File
import java.io.InputStream
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

private const val ASSETS_DIR = "assets/devtools"

@Volatile
private var installed = false

/** Whether debugger-enabled libraries were swapped in. */
@Volatile
private var swapped = false

/** Path to the swapped libhermesvm.so, if only Hermes was swapped. */
@Volatile
private var hermesLibraryPath: String? = null

/**
 * A tweak that makes React Native DevTools work in release builds.
 *
 * Release builds of React Native strip the debugger out of `libhermesvm.so`, `libhermestooling.so` and `libreactnative.so`.
 * This tweak swaps them (and `libjsi.so`) for the `debugOptimized` builds published on Maven Central, which are ABI-compatible.
 * The libraries are only swapped if the app ships the exact stock `release` libraries they were built alongside.
 *
 * Apps that ship a fork of React Native with stock Hermes only get `libhermesvm.so` swapped, and [HermesDevTools] provides the inspector instead of React Native.
 *
 * Requires [enableDevSupport] so that React Native connects to the Metro inspector proxy.
 *
 * See `docs/react-native-devtools.md` for details.
 */
val enableReactNativeDevTools by tweak {
    apply {
        val soLoader = classLoader.loadClassOrNull("com.facebook.soloader.SoLoader")
            ?: return@apply log.w("SoLoader not found, skipping")

        // Our libraries must be the first ones SoLoader sees, so prepend them as soon as SoLoader is initialized.
        XposedBridge.hookAllMethods(soLoader, "init", methodHook {
            after {
                if (installed) return@after
                installed = true

                try {
                    install(args[0] as Context, soLoader)
                } catch (e: Throwable) {
                    log.e("Failed to install DevTools libraries:", e)
                }
            }
        }.build())

        // Bundles loaded from assets must be remapped once loaded, or the debugger crashes the app when it sets a breakpoint
        listOf(
            // @Target: Same classes as scriptLoader hooks
            $$"com.facebook.react.runtime.ReactInstance$loadJSBundle$1",
            "com.facebook.react.bridge.CatalystInstanceImpl",
        ).mapNotNull { classLoader.loadClassOrNull(it) }.forEach { loader ->
            try {
                loader.method("loadScriptFromAssets", AssetManager::class.java, String::class.java, Boolean::class.javaPrimitiveType)
                    .hook {
                        after {
                            if (!swapped) return@after

                            val apks = listOf(appInfo.sourceDir, modulePath) + appInfo.splitSourceDirs.orEmpty()
                            val remapped = AssetMappings.privatize(apks.toTypedArray())
                            log.d("Remapped $remapped asset mapping(s) as private for the debugger")
                        }
                    }
            } catch (e: Throwable) {
                log.e("Failed to hook ${loader.name}, the debugger may crash the app when setting breakpoints:", e)
            }
        }

        // When only Hermes was swapped, Covalent provides the inspector once the runtime exists
        val reactInstance = classLoader.loadClassOrNull("com.facebook.react.runtime.ReactInstance")
            ?: return@apply log.w("ReactInstance not found, DevTools will only work with stock React Native")

        XposedBridge.hookAllConstructors(reactInstance, methodHook {
            after {
                val hermesPath = hermesLibraryPath ?: return@after

                try {
                    HermesDevTools.install(runtimeExecutorPointer(thisObject!!), hermesPath, modulePath, appInfo, classLoader)
                } catch (e: Throwable) {
                    log.e("Failed to start Hermes DevTools:", e)
                }
            }
        }.build())
    }
}

private fun Tweak.install(context: Context, soLoader: Class<*>) {
    val abi = currentAbi()

    ZipFile(modulePath).use { module ->
        val manifest = Properties().apply {
            val entry = module.getEntry("$ASSETS_DIR/manifest.properties")
                ?: return log.e("No DevTools libraries bundled in module")
            module.getInputStream(entry).use { load(it) }
        }

        if (!installReactNative(context, soLoader, module, manifest, abi)) {
            installHermes(context, soLoader, module, manifest, abi)
        }
    }
}

/** Swaps the whole set of React Native libraries, so React Native's own inspector works. Only possible if the app ships the exact stock libraries. */
private fun Tweak.installReactNative(context: Context, soLoader: Class<*>, module: ZipFile, manifest: Properties, abi: String): Boolean {
    val bundledVersion = manifest.getProperty("reactNativeVersion")
    val appVersion = reactNativeVersion()
    if (appVersion != bundledVersion) {
        log.w("App uses React Native $appVersion, but DevTools libraries are for $bundledVersion")
        return false
    }

    val libs = manifest.getProperty("libs").split(",")

    // A hash mismatch means the app ships a fork or a source build of React Native, which the debugOptimized libraries may not be compatible with.
    for (lib in libs) {
        val expected = manifest.getProperty("release.$abi.$lib")
        val actual = openAppLibrary(abi, lib)?.use { sha256(it) }
        if (actual == null) {
            log.w("Could not find $lib for $abi in app")
            return false
        }

        if (actual != expected) {
            log.w("$lib does not match stock React Native $bundledVersion ($actual != $expected)")
            return false
        }
    }

    val dir = extractLibraries(module, "$ASSETS_DIR/$abi", libs, File(context.codeCacheDir, "covalent/devtools/$bundledVersion/$abi"))
    prependSoSource(soLoader, dir)
    swapped = true

    log.i("Installed debugOptimized React Native $bundledVersion libraries for $abi from $dir")
    return true
}

/**
 * Swaps only libhermesvm.so, for apps that ship a fork of React Native but stock Hermes.
 * Since debugger in the app's React Native libraries is stripped out, so [HermesDevTools] drives the CDP agent instead.
 */
private fun Tweak.installHermes(context: Context, soLoader: Class<*>, module: ZipFile, manifest: Properties, abi: String) {
    val actual = openAppLibrary(abi, "libhermesvm.so")?.use { sha256(it) }
        ?: return log.w("Could not find libhermesvm.so for $abi in app, skipping")

    // Hermes bundled with the full set of libraries can be used on its own as well
    val candidates = mapOf(
        manifest.getProperty("hermesVersion") to ("release.$abi.libhermesvm.so" to "$ASSETS_DIR/$abi"),
    ) + manifest.getProperty("hermesOnlyVersions").split(",").filter { it.isNotEmpty() }.associateWith { version ->
        "hermes.$version.release.$abi" to "$ASSETS_DIR/hermes/$version/$abi"
    }

    val (version, location) = candidates.entries.firstOrNull { (_, location) -> manifest.getProperty(location.first) == actual }
        ?.toPair()
        ?: return log.w("libhermesvm.so does not match any bundled stock Hermes ($actual), skipping")

    val dir = extractLibraries(module, location.second, listOf("libhermesvm.so"), File(context.codeCacheDir, "covalent/devtools/hermes/$version/$abi"))
    prependSoSource(soLoader, dir)
    hermesLibraryPath = File(dir, "libhermesvm.so").path
    swapped = true

    log.i("Installed debugOptimized Hermes $version for $abi from $dir, React Native DevTools will be provided by Covalent")
}

/**
 * React Native runs bytecode bundles from assets straight out of a read-only shared memory map of the APK.
 * Hermes sets breakpoints (including the ones used for stepping) by patching the bytecode in place,
 * which needs `mprotect` to make it writable. That fails for these mappings, and Hermes aborts.
 *
 * See `cpp/mappings.cpp`.
 */
private object AssetMappings {
    init {
        System.loadLibrary("covalent")
    }

    /** Remaps read-only shared mappings of [paths] as private, so they can be made writable. Returns the number remapped. */
    fun privatize(paths: Array<String>) = nativePrivatize(paths)

    @JvmStatic
    private external fun nativePrivatize(paths: Array<String>): Int
}

private fun extractLibraries(module: ZipFile, from: String, libs: List<String>, to: File): File {
    to.mkdirs()

    for (lib in libs) {
        val entry = module.getEntry("$from/$lib")
        val file = File(to, lib)
        if (file.length() == entry.size) continue

        val temp = File(to, "$lib.tmp")
        module.getInputStream(entry).use { input -> temp.outputStream().use { input.copyTo(it) } }
        temp.renameTo(file)
    }

    return to
}

/**
 * Loading through SoLoader keeps the libraries in the app's linker namespace.
 * Loading them from the module's class loader would leave the app loading its own copies.
 */
private fun Tweak.prependSoSource(soLoader: Class<*>, dir: File) {
    val directorySoSource = classLoader.loadClassOrNull("com.facebook.soloader.DirectorySoSource")
    if (directorySoSource != null) {
        val resolveDependencies = XposedHelpers.getStaticIntField(directorySoSource, "RESOLVE_DEPENDENCIES")
        val source = XposedHelpers.newInstance(directorySoSource, dir, resolveDependencies)
        XposedHelpers.callStaticMethod(soLoader, "prependSoSource", source)
        return
    }

    // Apps minified with R8 rename SoLoader's internals and strip prependSoSource, so they are found by shape instead.
    // SoLoader keeps its sources in a static SoSource[], whose toString() still names the original classes.
    val sourcesField = soLoader.declaredFields.firstOrNull { field ->
        Modifier.isStatic(field.modifiers) && field.type.isArray &&
                (field.apply { isAccessible = true }.get(null) as? Array<*>)?.any { it.toString().contains("SoSource[") } == true
    } ?: throw IllegalStateException("Could not find SoLoader sources")

    val sources = sourcesField.get(null) as Array<*>
    val directorySource = sources.firstOrNull { it.toString().startsWith("DirectorySoSource[") }
        ?: throw IllegalStateException("Could not find DirectorySoSource")

    val constructor = directorySource.javaClass.declaredConstructors.first {
        it.parameterTypes.size >= 2 && it.parameterTypes[0] == File::class.java && it.parameterTypes[1] == Int::class.javaPrimitiveType
    }.apply { isAccessible = true }

    // DirectorySoSource.RESOLVE_DEPENDENCIES that R8 inlines
    val arguments = arrayOfNulls<Any>(constructor.parameterTypes.size).apply {
        this[0] = dir
        this[1] = 1
    }

    @Suppress("UNCHECKED_CAST")
    val updated = java.lang.reflect.Array.newInstance(sourcesField.type.componentType!!, sources.size + 1) as Array<Any?>
    updated[0] = constructor.newInstance(*arguments)
    System.arraycopy(sources, 0, updated, 1, sources.size)
    sourcesField.set(null, updated)
}

private fun Tweak.currentAbi(): String =
    runCatching { XposedHelpers.getObjectField(appInfo, "primaryCpuAbi") as String? }.getOrNull()
        ?: (if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS).first()

private fun Tweak.reactNativeVersion(): String? {
    val clazz = classLoader.loadClassOrNull("com.facebook.react.modules.systeminfo.ReactNativeVersion") ?: return null
    val version = XposedHelpers.getStaticObjectField(clazz, "VERSION") as Map<*, *>
    val prerelease = version["prerelease"]?.let { "-$it" } ?: ""
    return "${version["major"]}.${version["minor"]}.${version["patch"]}$prerelease"
}

/** Opens a native library of the app, whether it was extracted to disk or is stored uncompressed in one of the APKs. */
private fun Tweak.openAppLibrary(abi: String, lib: String): InputStream? {
    appInfo.nativeLibraryDir?.let { File(it, lib) }?.takeIf { it.exists() }?.let { return it.inputStream() }

    for (apk in listOf(appInfo.sourceDir) + appInfo.splitSourceDirs.orEmpty()) {
        val zip = ZipFile(apk)
        val entry = zip.getEntry("lib/$abi/$lib")
        if (entry == null) {
            zip.close()
            continue
        }

        return object : java.io.FilterInputStream(zip.getInputStream(entry)) {
            override fun close() {
                super.close()
                zip.close()
            }
        }
    }

    return null
}

private fun sha256(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
