package me.palmdevs.covalent

// Change this to the actual class name of the main Activity of the target app.
// Currently, this hooks all activities, but that may not be desirable.
const val TARGET_ACTIVITY_CLASS = "android.app.Activity"

/**
 * Hermes bytecode assets to load into the target app.
 *
 * Place the compiled Hermes bytecode bundle in `app/src/main/assets` and add its path to this list.
 * The framework will load and execute the bundle in the target app.
 */
val scriptAssets = listOf(
    // The example bundle logs into the console when it is loaded.
    // Do logcat | grep ReactNativeJS to see the logs.
    "assets://example.bundle"
)