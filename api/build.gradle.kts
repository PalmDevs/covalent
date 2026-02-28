import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
    signing
}

android {
    namespace = "me.palmdevs.covalent.api"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
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

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    compileOnly(libs.xposed.api)
    implementation(libs.android.core.ktx)
}

publishing {
    publications {
        create("release", MavenPublication::class) {
            groupId = "me.palmdevs.covalent.api"
            artifactId = "covalent-api"
            version = rootProject.properties["version"] as String

            afterEvaluate {
                from(components["release"])
            }
        }
    }
}

signing {
    sign(publishing.publications["release"])

    val keyId = project.findProperty("signing.keyId")
    val secretKey = project.findProperty("signing.secretKey")

    // Fallback to GPG command line if the properties are not set
    keyId ?: secretKey ?: useGpgCmd()

    useGpgCmd()
}