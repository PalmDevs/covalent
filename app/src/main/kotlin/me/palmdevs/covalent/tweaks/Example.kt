package me.palmdevs.covalent.tweaks

import me.palmdevs.covalent.api.tweak

/**
 * An example tweak that logs a message when it is loaded.
 */
val example by tweak {
    apply { appInfo, classLoader ->
        log.i("Hello from Covalent! This is the native side.")
        log.i("Covalent loaded in: ${appInfo.packageName}, with classloader: $classLoader")
    }
}