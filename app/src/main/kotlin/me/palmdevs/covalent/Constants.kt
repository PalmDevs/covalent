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
val scriptAssets = listOf<String>(
    // The example bundle logs into the console when it is loaded.
    // Do logcat | grep ReactNativeJS to see the logs.
    "assets://example.bundle",
    // @Target: DevTools needs updating in order to keep up with updates.
    "assets://react-devtools.bundle",
    // Lets React DevTools edit props and hook state with production renderers, requires react-devtools.bundle
    "assets://react-devtools-editing.bundle",
    // Connects React DevTools to React Native DevTools, requires react-devtools.bundle
    "assets://react-devtools-fusebox.bundle",
    // Lets React DevTools select elements by tapping them, requires react-devtools.bundle
    "assets://react-devtools-inspector.bundle",
    // Sets __DEV__ to true enabling dev-only features that aren't stripped.
    "assets://dev-constants.bundle",
)