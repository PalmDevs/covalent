import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "me.palmdevs.covalent"
    compileSdk = 36

    defaultConfig {
        applicationId = "me.palmdevs.covalent"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = rootProject.properties["version"] as String
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}