# React Native DevTools in release apps

Research notes on getting React Native DevTools (Fusebox) working against production React Native apps by swapping in debugger-enabled native libraries.

> [!NOTE]
> Everything here targets **React Native 0.86.3** with **Hermes V1** (`com.facebook.hermes:hermes-android:250829098.0.17`, bytecode version 98), New Architecture, on Android.  
> Findings come from reading the RN and Hermes sources at those tags and from diffing the prebuilt `release` and `debugOptimized` AARs published on Maven Central.

## Summary

- You do **not** have to recompile Hermes for apps that use stock React Native.
  React native publishes a `debugOptimized` variant of both `react-android` and `hermes-android`. These are release-optimized builds with the debugger compiled in,
  and they are ABI-compatible drop-in replacements for the `release` variant.
- Swapping **Hermes alone is not enough** to get DevTools through React Native's own stack.
  The debugger is compiled out in three libraries: `libhermesvm.so`, `libhermestooling.so` and `libreactnative.so`.
- No binary merging is needed. `libreactnative.so` is already a merged library upstream, and you replace whole libraries.
- Hermes V1 (Static Hermes) is not an obstacle. React Native still runs Hermes bytecode in the VM (JIT is off, `HERMESVM_ALLOW_JIT=0`), and the public API is behind stable `jsi::ICast` interfaces.
- The main practical limit is **debug info**. Production bundles are compiled with `-O -output-source-map`, which strips the debug-info section,
  so there are no sources, breakpoints or stepping in the app's own code. **Console, eval, object inspection, heap snapshots and CPU profiling still work.**

## Where the debugger is compiled out

By default, React Native defines `HERMES_ENABLE_DEBUGGER` and `REACT_NATIVE_DEBUGGER_ENABLED` only if `${CMAKE_BUILD_TYPE} MATCHES Debug OR REACT_NATIVE_DEBUG_OPTIMIZED`,
so Hermes is built without `-DHERMES_ENABLE_DEBUGGER=1` for release.

| Library               | Release behaviour                                                                                                                                                                                                                          | Source                                                                       |
| --------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------- |
| `libhermesvm.so`      | No `hermes::cdp::CDPAgent`, `CDPDebugAPI`, `debugger::AsyncDebuggerAPI` (98 missing exports). Memory instrumentation (heap snapshots) also follows this flag.                                                                              | `ReactAndroid/hermes-engine/build.gradle.kts`, Hermes `CMakeLists.txt`       |
| `libhermestooling.so` | `HermesRuntimeTargetDelegate` uses `FallbackRuntimeTargetDelegate`, so it never talks to the Hermes CDP agent.                                                                                                                             | `ReactCommon/hermes/inspector-modern/chrome/HermesRuntimeTargetDelegate.cpp` |
| `libreactnative.so`   | `HostAgent::Impl` is a stub whose `handleRequest` is empty, so **every CDP request is dropped** before it reaches the instance or runtime agents. `InspectorFlags::fuseboxEnabled` falls back to the `fuseboxEnabledRelease` feature flag. | `ReactCommon/jsinspector-modern/HostAgent.cpp`, `InspectorFlags.cpp`         |

CDP messages travel along this path:

```text
Metro inspector proxy -ws-> CxxInspectorPackagerConnection (libreactnative)
                            `-> HostTarget > HostSession > HostAgent::handleRequest   <-- stubbed in release
                                                           `-> InstanceAgent > RuntimeAgent
                                                                `-> HermesRuntimeAgentDelegate (libhermestooling)
                                                                     `-> hermes::cdp::CDPAgent (libhermesvm)
```

The Java side is **not** stripped:

- `DevServerHelper.openInspectorConnection()` runs whenever a `DevSupportManagerBase` exists. Covalent already forces `BridgelessDevSupportManager` through `useDevSupport = true`.
- `InspectorFlags.getFuseboxEnabled()` is a JNI call, so it returns `true` as soon as the debug `libreactnative.so` is loaded.
- The `ReactBuildConfig.DEBUG` checks in `ReactHostImpl` only guard asserts and logging.

## Why the `debugOptimized` libraries are drop-in replacements

These checks were run on the arm64-v8a libraries of `react-android:0.86.3` and `hermes-android:250829098.0.17`:

- **Exports:** every `debugOptimized` library exports a strict superset of `release`:
  - `libreactnative`: +4 symbols
  - `libhermestooling`: +47
  - `libhermesvm`: +98
  - `libjsi`: identical

  So third-party libraries built against release, such as `libappmodules.so`, still resolve.
- **Imports:** every new import of the debug `libhermestooling` and `libhermesvm` resolves against the other libraries in the set.
- **JNI:** the set of `com/facebook/...` class descriptors referenced by each library is identical across variants.
- **Java:** `javap -p` of `classes.jar` is identical except for `BuildConfig.DEBUG` and `BuildConfig.BUILD_TYPE` (after normalising Kotlin's `$ReactAndroid_<variant>` internal-name suffix).
  The app's R8-processed dex matches the debug natives.
- **Headers:** `hermes/hermes.h` has no `HERMES_ENABLE_DEBUGGER` conditionals. `HermesRuntime` / `IHermesRootAPI` are reached through `castInterface`.
  On the RN side, `HostAgent` and `HermesRuntimeTargetDelegate` use a private implementation pattern specifically so their layout does not depend on the debugger defines.
- **Shared runtime libraries:** `libc++_shared.so` and `libfbjni.so` are byte-identical across variants.

> [!WARNING]
> **Use `debugOptimized`, not `debug`.** The unoptimized `debug` variant is missing a few inline symbols that `release` exports,
> such as `std::basic_string::__init_copy_ctor_external` and a `jni::base_owned_ref` destructor. Consumers could fail to link against it, and it is ~3x larger.

## The debug-info limitation

The React Native Gradle plugin compiles release bundles with `hermesFlags = ["-O", "-output-source-map"]`.
In `hermesc`, `-output-source-map` sets `stripDebugInfoSection = true`, so the shipped `.hbc` has no debug-info section. The source map stays on the build machine.

Hermes's debugger skips any runtime module without debug info (`Debugger::getLoadedScripts`, `Debugger::resolveBreakpointLocation`). For a stock production app, that gives:

| Works                                                          | Does not work (for the app's own bundle) |
| -------------------------------------------------------------- | ---------------------------------------- |
| Console (`console.*` forwarding, `Runtime.consoleAPICalled`)   | Sources panel / `Debugger.scriptParsed`  |
| `Runtime.evaluate` REPL (the release VM includes the compiler) | Breakpoints and stepping                 |
| Object inspection (`Runtime.getProperties`)                    | Symbolicated stack frames and profiles   |
| Heap snapshots (`HeapProfiler`)                                |                                          |
| CPU profiling (unsymbolicated)                                 |                                          |
| Pausing                                                        |                                          |

Covalent's own scripts can be compiled with `-g` and without `-output-source-map`, so injected code gets full source-level debugging.

## Approaches

### A. Swap the full native set (used for stock React Native)

Replace `libhermesvm.so`, `libhermestooling.so`, `libreactnative.so` and `libjsi.so` with the `debugOptimized` builds of the **exact** versions the app has.

- You get the complete React Native DevTools experience through Metro's inspector proxy, including the RN-specific domains such as network, tracing and the "paused in debugger" overlay.
- The libraries must be loaded **before** the app's own copies and **in the app's linker namespace**.
  If they are loaded from the Xposed module's class loader instead, the app ends up with two copies.
  Covalent does this by prepending a SoLoader `DirectorySoSource`, so SoLoader itself calls `System.load` from the app's class loader.
  - Merged names such as `rninstance` and `hermesinstancejni` are mapped to `reactnative` and `hermestooling` by `OpenSourceMergedSoMapping`.
  - Later `DT_NEEDED libreactnative.so` entries, for example from `libappmodules.so`, resolve to the already-loaded library by soname.
- Detect whether an app uses stock RN by comparing SHA-256 hashes of its `libreactnative.so` / `libhermesvm.so` against the Maven `release` artifacts.
  If they don't match, the app ships a fork and the swap is not safe.

### B. Swap only `libhermesvm.so` and drive CDP from Covalent (used for forks)

- This is ABI-safe on its own: the release `libhermestooling` and `libreactnative` imports are all covered by the debug `libhermesvm` exports.
- Covalent creates `hermes::cdp::CDPDebugAPI` and `CDPAgent` itself, using the `jsi::Runtime` and `RuntimeExecutor` it already has,
- and registers with Metro's `/inspector/device` protocol the same way React Native does.
- This only depends on matching the **Hermes** version and works with forked `libreactnative` builds.
- You lose everything that lives in `HostAgent`, such as network inspection, tracing and the "paused in debugger" overlay.
- Covalent takes over the host-level work the frontend relies on:
  - `Runtime.executionContextCreated` on `Runtime.enable`. React Native's `InstanceAgent` sends it, not the Hermes agent. Without it, the console can neither evaluate nor show messages.
  - `Runtime.addBinding`, implemented as a native global that emits `Runtime.bindingCalled`.
  - `ReactNativeApplication.enable` / `metadataUpdated`.
  - Acknowledging `FuseboxClient.setClientMetadata`, `Log.enable`/`disable` and `Overlay.setPausedInDebuggerMessage`.

### When would recompiling be needed?

Only when the app ships a forked or source-built React Native or Hermes.
You then need that exact source tree, built with `-DCMAKE_BUILD_TYPE=Release -DREACT_NATIVE_DEBUG_OPTIMIZED=True` for React Native and `-DHERMES_ENABLE_DEBUGGER=True` for Hermes.

For closed forks, approach B avoids touching `libreactnative.so` at all.

## Version matching

| React Native | Hermes (V1, default) | Bytecode | Legacy Hermes (opt-out) |
| ------------ | -------------------- | -------- | ----------------------- |
| 0.86.3       | `250829098.0.17`     | 98       | `0.17.0`                |

- Hermes ships a new patch version for every RN patch, and a new major line (`260318099.x`) is already published, so  match versions exactly per app.
- The Hermes version for a given RN release is in `packages/react-native/sdks/hermes-engine/version.properties`.
- Both the V1 and legacy `hermes-android` artifacts publish `debug`, `debugOptimized` and `release` variants.

## Proof of concept

Tested on an x86_64 Android 16 emulator (Vector/LSPosed), using the [sample app](https://github.com/PalmDevs/covalent-sample-app) on React Native 0.86.3 and built as a **release** APK.

### How it works

- `app/build.gradle.kts` has a `prepareDevToolsLibs` task. For each supported ABI, it downloads the `release` and `debugOptimized` AARs of `react-android` and `hermes-android` from Maven Central.
  It then packs the `debugOptimized` libraries into `assets/devtools/<abi>/` and writes the SHA-256 hashes of their `release` counterparts to `assets/devtools/manifest.properties`.
- The `enableReactNativeDevTools` tweak (`tweaks/EnableReactNativeDevTools.kt`) hooks `SoLoader.init`. Once SoLoader is initialized, it:
  1. checks that the app's React Native version (`ReactNativeVersion.VERSION`) matches the bundled libraries.
  2. checks that the app's `libreactnative.so`, `libhermestooling.so`, `libjsi.so` and `libhermesvm.so` hash to the stock `release` builds,
     whether they are extracted or stored uncompressed in the APK. If any hash differs, the app ships a fork and the tweak skips it.
  3. extracts the `debugOptimized` libraries to `code_cache/covalent/devtools/<version>/<abi>/`;
  4. calls `SoLoader.prependSoSource(DirectorySoSource(dir, RESOLVE_DEPENDENCIES))`, so SoLoader loads them from the app's class loader and linker namespace.
- `enableDevSupport` forces `useDevSupport`, so React Native opens the inspector connection to Metro.

### Results

- `/proc/<pid>/maps` shows all four libraries mapped only from Covalent's copies. The app's release copies are never loaded.
- The app runs normally. `libappmodules.so` and `libreact_codegen_safeareacontext.so`, which were built against the release headers, link against the swapped `libreactnative.so` without problems.
- Metro lists a `React Native Bridgeless [C++ connection]` target. React Native DevTools connects to it and works.
- `bun scripts/devtools-smoke-test.ts` reports that `Runtime.enable`, `Debugger.enable`, `Runtime.evaluate`, console forwarding (`Runtime.consoleAPICalled`) and `Runtime.getHeapUsage` all succeed.
  Hermes reports `{"Bytecode Version": 98, "Static Hermes": true, "OSS Release Version": "250829098.0.17"}`.

### Caveats

- **Which bundle gets loaded.** With dev support forced on and Metro running, React Native loads the bundle Metro serves (`BridgelessReactNativeDevBundle.js`), not the embedded one.
  For the sample app that is its own dev bundle, which has debug info, a development React build and the React DevTools backend, so every DevTools tab works.
  - For a third-party app, Metro would serve at all or serve the wrong bundle.
  - Setting `ReactHostImpl`'s `allowPackagerServerAccess` to `false` keeps the embedded bundle while the inspector stays connected.
    But the **Components** tab then doesn't load, because production bundles have their React DevTools backend stripped out.
  - Getting it back needs an injected backend (`react-devtools.bundle`) wired to Fusebox's React DevTools dispatcher.
- **Metro checks the origin.** Metro's inspector proxy rejects debugger WebSocket connections that don't send an allowed `Origin` header, such as `http://localhost:8081`.

### Case study: Discord (345.5 Alpha, RN 0.86.0)

Discord shows why the hash check is important. Compared with Maven's release artifacts on x86_64:

| Library               | Discord                                                                                                                              |
| --------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| `libhermesvm.so`      | Byte-identical to stock `hermes-android:250829098.0.15`. RN 0.86.0 pins `0.14`, so Discord overrides the Hermes version.             |
| `libreactnative.so`   | Custom build of a fork: 71 extra exports (extra feature flags and their JNI entry points, Fabric `measureAsyncOnUI`/`scheduleMount`) |
| `libhermestooling.so` | Custom build, linked against the fork                                                                                                |
| `libjsi.so`           | Custom build                                                                                                                         |

`libreanimated.so` and `libreact_codegen_ReanimatedView.so` import a symbol that only the fork exports.
Swapping in stock `libreactnative.so` would therefore fail to load, and Discord's extra `ReactNativeFeatureFlagsCxxInterop` natives would fail to register.0 **Approach A is not possible here.**

Approach B is possible, because `libhermesvm.so` is stock and `hermes-android:250829098.0.15` has a `debugOptimized` variant.

Missing symbols are only the visible part of the problem. The fork, which is public as `discord/react-native` (tags `0.86.0-discord-*`, about 127 native files changed), also **changes class layouts in public headers**.
For example, it adds fields to `TextAttributes` and `HostPlatformViewProps`. Codegen and third-party libraries compiled against those headers, such as `libreact_codegen_*.so` and Reanimated, expect the fork's layouts.
Pairing them with stock `libreactnative.so`, or with a binary merge that brings in stock code, would corrupt memory silently even where every symbol resolves.
A fork can only be paired with a build of the **same fork source**, rebuilt with `-DREACT_NATIVE_DEBUG_OPTIMIZED=True`.

This does not affect apps on stock React Native. Their codegen libraries are compiled against the same headers as both the `release` and the `debugOptimized` `libreactnative.so`:

- the debugger defines are `PRIVATE` compile options.
- `HostAgent` and `HermesRuntimeTargetDelegate` use a private implementation pattern, so their layout is fixed.
- `EventLoopReporter`, the only other header whose layout changes, is only included from `.cpp` files inside `libreactnative.so`.
- both variants are Release builds with `NDEBUG` defined.

The sample app's `libappmodules.so` and `libreact_codegen_safeareacontext.so` confirm this in practice.

### Approach B implementation

- `prepareDevToolsLibs` also packs `debugOptimized` `libhermesvm.so` for each version in `devToolsHermesOnlyVersions`, with the SHA-256 of its `release` counterpart.
  The Hermes version bundled for approach A can be used on its own too.
- When the full set doesn't match, `enableReactNativeDevTools` hashes the app's `libhermesvm.so`. If it matches a bundled stock Hermes, only that library is swapped.
  - R8 renames SoLoader's `DirectorySoSource` and strips `prependSoSource` in minified apps such as Discord.
  - SoLoader's static `SoSource[]` is therefore found by shape, since the sources' `toString()` still names the original classes, and the new source is prepended to it directly.
- **Native bridge** (`app/src/main/cpp/devtools.cpp`):
  - `libcovalent.so` lives in the Xposed module's isolated linker namespace, so it cannot link against the app's libraries.
  - The six Hermes entry points it needs are resolved from `libhermesvm.so`'s `.dynsym` on disk, using the load bias from `dl_iterate_phdr`.
  - Hermes types whose destructors would need linking, such as `std::unique_ptr<CDPAgent>` and `cdp::State`, are replaced by ABI-equivalent opaque types.
  - `CDPAgent` is destroyed by calling its resolved destructor.
  - The `HermesRuntime` is obtained through `castInterface(IHermes::uuid)`.
- **Console:** React Native's console forwarding lives in the stubbed parts of `libreactnative.so`. Covalent traps assignments to `globalThis.console`, because the bundle replaces `console` during initialization.
  It forwards each call to `CDPDebugAPI::addConsoleMessage` with a stack trace from `Debugger::captureStackTrace`.
- **Transport** (`tweaks/HermesDevTools.kt`):
  - Uses a Ktor WebSocket client to connect to Metro's inspector proxy.
  - Implements Metro's device protocol (`getPages`, `connect`, `disconnect`, `wrappedEvent`) and reconnects until Metro is running.
  - CDP messages cross JNI as UTF-8 byte arrays, avoiding JNI's modified UTF-8.

Results on Discord:

- Only Covalent's `libhermesvm.so` is mapped, and Discord runs normally.
- Metro lists `Discord (Covalent)`. `Runtime.evaluate` reports `{"OSS Release Version": "250829098.0.15", "Static Hermes": true}`.
- Console messages logged since startup are replayed on connect, with stack traces.
- **Discord's bundle keeps location info.** `Debugger.scriptParsed` reports the bundle, with its CI build path as the URL.
  `Debugger.pause` paused within about 100 ms at `emit`, line 116, column 730. `Debugger.evaluateOnCallFrame` and `Debugger.resume` work.
- There are no sources: the bundle's URL is a path on Discord's CI machine, and the source map isn't shipped.

Known limitations:

- **Components tab:** Needs the React DevTools backend and RN's `__FUSEBOX_REACT_DEVTOOLS_DISPATCHER__`.
  React Native strips them in production builds (see RN's `setUpReactDevTools.js`), so Covalent loads two scripts before the app's bundle, both built as plain JS with `bun scripts/compile-script.ts <file>`:
  - `react-devtools.bundle` installs the hook.
  - `react-devtools-fusebox.bundle` (`app/src/main/ts/react-devtools-fusebox.ts`) defines the dispatcher and calls `connectWithCustomMessagingProtocol` when the frontend initializes the `react-devtools` domain.

  On Discord, the backend sends `backendInitialized` and the component tree (`operations`) through the binding. Names come from Discord's production React build.
- **Editing props and hook state:** production renderers don't register `overrideProps`, `overrideHookState` and related functions with the React DevTools hook, so DevTools disables editing.
  `react-devtools-editing.bundle` (`app/src/main/ts/react-devtools-editing.ts`) wraps `hook.inject` and adds ports of React 19.2's development implementations before React registers its renderer.
  - The one production gap is scheduling a render of an arbitrary Fiber. React's class updater does that for any Fiber through `enqueueForceUpdate({ _reactInternals: fiber })`.
    A throwaway `updateQueue` receives the class update, which function components and host components don't have.
  - Class components already work without this, because the backend edits them itself and calls `forceUpdate()`.
  - On Discord, a `useState` edit re-rendered the component with the new value, and a `testID` edit on an `RCTView` was committed.
  - As in development builds, a prop edit lasts until the parent renders again.
- **Tapping to select elements, and highlighting:** React Native's `ReactDevToolsOverlay` is only rendered in development builds. Covalent provides the same behaviour in two parts:
  - `react-devtools-inspector.bundle` (`app/src/main/ts/react-devtools-inspector.ts`) listens to the agent's `startInspectingNative`, `stopInspectingNative`, `showNativeHighlight` and `hideNativeHighlight` events.
    For a touch, it finds the tapped view with Fabric's `findNodeAtPoint`, aligning coordinates with `measureInWindow`.
    It then calls `agent.selectNode` with the view's public instance, or the instance itself, whichever DevTools registered.
  - The `elementInspector` tweak (`tweaks/ElementInspector.kt`, `cpp/inspector.cpp`) consumes touches at the window's `DecorView` while inspecting, so the app doesn't react to them.
    It passes them to JS in dp, and draws highlights on the current Activity's window overlay. JS and Kotlin talk through JSI host functions, so this doesn't depend on which approach is used.
  - Touches inside other windows, such as `Modal`s rendered as dialogs, aren't mapped yet.
- **The dev menu's element inspector** (the in-app inspector panel) is not provided. `AppContainer` only renders it under `__DEV__`. The React DevTools selection above covers the same use case.
- Hermes rejects `Runtime.evaluate` expressions containing characters outside the BMP (e.g. emojis). They arrive as lone surrogates, which appears to be a Hermes CDP issue rather than the bridge's.

- `CDPDebugAPI` is never destroyed. If React Native recreates its instance without restarting the process, the old runtime is destroyed underneath it.
  Covalent hooks reloads to restart the process fully, so this only matters for instance restarts React Native triggers itself.
- The console shim's wrapper is the top frame of console stack traces. When the app wraps `console` methods itself, only the outermost call is reported, so each call is logged once.
- Only the main runtime is exposed. Worklet runtimes (Reanimated) are not.

### Reproducing

1. Build and install Covalent. Enable it for the target app in your Xposed manager.
2. Start Metro (any React Native project on 0.86.3 works as the inspector proxy) and run `adb reverse tcp:8081 tcp:8081`.
3. Launch the app, then open React Native DevTools, either from Metro (`j`) or from `http://localhost:8081/json/list`.
