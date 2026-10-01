@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val supportedAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

android {

    buildFeatures {
        prefab = true
    }

    namespace = "me.palmdevs.covalent"
    compileSdk = 36

    defaultConfig {
        applicationId = "me.palmdevs.covalent"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = rootProject.properties["version"] as String

        ndk {
            abiFilters += supportedAbis
        }
    }

    buildTypes {
        all {
            externalNativeBuild {
                cmake {
                    targets("covalent")
                    abiFilters(*supportedAbis.toTypedArray())
                }
            }
        }

        release {
            isMinifyEnabled = false

            externalNativeBuild {
				cmake {
					arguments("-DCMAKE_BUILD_TYPE=MinSizeRel")
				}
			}
        }

        debug {
			externalNativeBuild {
				cmake {
					arguments("-DCMAKE_BUILD_TYPE=Debug")
				}
			}
		}
    }

    externalNativeBuild {
		cmake {
			path = file("src/main/cpp/CMakeLists.txt")
			version = "3.22.1"
		}
	}

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
}

dependencies {
    implementation(project(":api"))

    compileOnly(libs.xposed.api)
    implementation(libs.android.core.ktx)
    implementation(libs.ktor.client.cio)
}

// @Target: React Native DevTools support swaps in the debugOptimized native libraries of these exact versions.
// See docs/react-native-devtools.md.
val devToolsReactNativeVersion = "0.86.3"
val devToolsHermesVersion = "250829098.0.17"

// Hermes versions to bundle debugOptimized libhermesvm.so for, used with apps that ship a fork of React Native.
// Covalent then drives the Hermes CDP agent itself instead of React Native's inspector.
val devToolsHermesOnlyVersions = listOf(
    "250829098.0.15",
)

/**
 * Downloads the `release` and `debugOptimized` AARs of `react-android` and `hermes-android` from Maven Central,
 * then packs the debugOptimized libraries and the SHA-256 hashes of their release counterparts into assets.
 */
abstract class PrepareDevToolsLibsTask : DefaultTask() {
    @get:Input
    abstract val reactNativeVersion: Property<String>

    @get:Input
    abstract val hermesVersion: Property<String>

    @get:Input
    abstract val hermesOnlyVersions: ListProperty<String>

    @get:Input
    abstract val abis: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Internal
    abstract val cacheDir: DirectoryProperty

    @TaskAction
    fun prepare() {
        val rn = reactNativeVersion.get()
        val hermes = hermesVersion.get()
        val artifacts = mapOf(
            "com/facebook/react/react-android/$rn/react-android-$rn" to
                    listOf("libreactnative.so", "libhermestooling.so", "libjsi.so"),
            "com/facebook/hermes/hermes-android/$hermes/hermes-android-$hermes" to
                    listOf("libhermesvm.so"),
        )

        val out = outputDir.get().asFile.resolve("devtools").apply {
            deleteRecursively()
            mkdirs()
        }

        val manifest = Properties().apply {
            this["reactNativeVersion"] = rn
            this["hermesVersion"] = hermes
            this["libs"] = artifacts.values.flatten().joinToString(",")
        }

        for ((artifact, libs) in artifacts) {
            val release = ZipFile(download("$artifact-release.aar"))
            val debugOptimized = ZipFile(download("$artifact-debugOptimized.aar"))

            for (abi in abis.get()) for (lib in libs) {
                val entry = "jni/$abi/$lib"

                release.getInputStream(release.getEntry(entry)).use {
                    manifest["release.$abi.$lib"] = sha256(it.readBytes())
                }

                out.resolve("$abi/$lib").apply { parentFile.mkdirs() }.outputStream().use {
                    debugOptimized.getInputStream(debugOptimized.getEntry(entry)).copyTo(it)
                }
            }

            release.close()
            debugOptimized.close()
        }

        manifest["hermesOnlyVersions"] = hermesOnlyVersions.get().joinToString(",")

        for (version in hermesOnlyVersions.get()) {
            val artifact = "com/facebook/hermes/hermes-android/$version/hermes-android-$version"
            val release = ZipFile(download("$artifact-release.aar"))
            val debugOptimized = ZipFile(download("$artifact-debugOptimized.aar"))

            for (abi in abis.get()) {
                val entry = "jni/$abi/libhermesvm.so"

                release.getInputStream(release.getEntry(entry)).use {
                    manifest["hermes.$version.release.$abi"] = sha256(it.readBytes())
                }

                out.resolve("hermes/$version/$abi/libhermesvm.so").apply { parentFile.mkdirs() }.outputStream().use {
                    debugOptimized.getInputStream(debugOptimized.getEntry(entry)).copyTo(it)
                }
            }

            release.close()
            debugOptimized.close()
        }

        out.resolve("manifest.properties").outputStream().use { manifest.store(it, null) }
    }

    private fun download(path: String): File {
        val file = cacheDir.get().asFile.resolve(path.substringAfterLast('/'))
        if (file.exists()) return file

        logger.lifecycle("Downloading $path")
        file.parentFile.mkdirs()
        val temp = File(file.path + ".tmp")
        URI("https://repo1.maven.org/maven2/$path").toURL().openStream().use { input ->
            temp.outputStream().use { input.copyTo(it) }
        }
        temp.renameTo(file)
        return file
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

val prepareDevToolsLibs by tasks.registering(PrepareDevToolsLibsTask::class) {
    reactNativeVersion = devToolsReactNativeVersion
    hermesVersion = devToolsHermesVersion
    hermesOnlyVersions = devToolsHermesOnlyVersions
    abis = supportedAbis
    outputDir = layout.buildDirectory.dir("generated/devtools-assets")
    cacheDir = layout.buildDirectory.dir("devtools-cache")
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(prepareDevToolsLibs, PrepareDevToolsLibsTask::outputDir)
    }
}
