@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    val supportedAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

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

                    cppFlags += "-std=c++17" // Ensure you have C++17 or higher
                    // Add this line below to fix the STL mismatch:
                    arguments += "-DANDROID_STL=c++_shared"
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

    compileOnly(libs.react.android)
    implementation(libs.fbjni)

    compileOnly(libs.xposed.api)
    implementation(libs.android.core.ktx)
}