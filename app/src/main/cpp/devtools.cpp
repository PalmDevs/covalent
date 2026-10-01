// React Native DevTools support for apps that ship a fork of React Native.
//
// The app's libhermesvm.so is swapped for a debugOptimized build, then this file runs the Hermes CDP agent directly,
// since the debugger glue in the app's libreactnative.so and libhermestooling.so is stripped out.
//
// Since this library is loaded in the Xposed module's linker namespace, which cannot see the app's libraries,
// Hermes functions are found by demangling libhermesvm.so's dynamic symbol table.
//
// We have to make sure to never instantiate Hermes types that would pull in unresolvable symbols (e.g. destructors of CDPAgent).

#include <jni.h>
#include <android/log.h>
#include <cxxabi.h>
#include <fcntl.h>
#include <link.h>
#include <pthread.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <chrono>
#include <climits>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include <hermes/hermes.h>
#include <hermes/cdp/ConsoleMessage.h>
#include <jsi/jsi.h>

#include "JRuntimeExecutor.h"

#define LOG_TAG "Covalent"
#define LOGI(fmt, ...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, "hermesDevTools:native - " fmt, ##__VA_ARGS__)
#define LOGE(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "hermesDevTools:native - " fmt, ##__VA_ARGS__)

using namespace facebook;

namespace {
    using facebook::hermes::HermesRuntime;
    using RuntimeTask = std::function<void(HermesRuntime&)>;
    using EnqueueRuntimeTaskFunc = std::function<void(RuntimeTask)>;
    using OutboundMessageFunc = std::function<void(const std::string&)>;

    // A std::unique_ptr<T> returned by value, without instantiating the destructor of T.
    // It is returned the same way (through a hidden pointer) as std::unique_ptr.
    struct OpaqueUniquePtr {
        void* ptr = nullptr;
        ~OpaqueUniquePtr() {}
    };
    static_assert(sizeof(OpaqueUniquePtr) == sizeof(std::unique_ptr<int>));

    // facebook::hermes::cdp::State is a single std::unique_ptr, which is empty when default constructed.
    // It is passed the same way (through a pointer to a temporary) as facebook::hermes::cdp::State.
    struct OpaqueState {
        void* ptr = nullptr;
        ~OpaqueState() {}
    };

    // @Target: Hermes V1 (250829098.0.{15,17})
    // Found by name at runtime, so the pointer types must be kept in sync with the signatures documented here.
    struct HermesCDP {
        // static std::unique_ptr<CDPDebugAPI> CDPDebugAPI::create(HermesRuntime&, size_t)
        OpaqueUniquePtr (*createDebugAPI)(HermesRuntime&, size_t);
        // void CDPDebugAPI::addConsoleMessage(ConsoleMessage)
        void (*addConsoleMessage)(void* debugAPI, facebook::hermes::cdp::ConsoleMessage);
        // static std::unique_ptr<CDPAgent> CDPAgent::create(int32_t, CDPDebugAPI&, EnqueueRuntimeTaskFunc, OutboundMessageFunc, State)
        OpaqueUniquePtr (*createAgent)(int32_t, void* debugAPI, EnqueueRuntimeTaskFunc, OutboundMessageFunc, OpaqueState);
        // void CDPAgent::handleCommand(std::string)
        void (*handleCommand)(void* agent, std::string);
        // CDPAgent::~CDPAgent()
        void (*destroyAgent)(void* agent);
        // StackTrace Debugger::captureStackTrace() const
        facebook::hermes::debugger::StackTrace (*captureStackTrace)(const void* debugger);
    };

    JavaVM* gVm = nullptr;
    jclass gClass = nullptr;
    jmethodID gOnMessage = nullptr;
    jmethodID gOnReady = nullptr;
    jmethodID gOnBindingCalled = nullptr;
    pthread_key_t gDetachKey;

    HermesCDP gCdp {};
    react::RuntimeExecutor gExecutor;
    HermesRuntime* gRuntime = nullptr;
    facebook::hermes::IHermes* gHermes = nullptr;
    void* gDebugAPI = nullptr;
    std::string gConsoleScript;

    std::mutex gAgentMutex;
    void* gAgent = nullptr;

    struct LoadedLibrary {
        const char* path;
        std::string resolvedPath;
        uintptr_t bias = 0;
        bool found = false;
    };

    bool findLoadedLibrary(const char* path, uintptr_t& bias, std::string& loadedPath) {
        char wanted[PATH_MAX];
        if (!realpath(path, wanted)) return false;

        LoadedLibrary library { wanted };
        dl_iterate_phdr([](dl_phdr_info* info, size_t, void* data) {
            auto* library = static_cast<LoadedLibrary*>(data);
            char actual[PATH_MAX];
            if (!info->dlpi_name || !realpath(info->dlpi_name, actual)) return 0;
            if (strcmp(actual, library->path) != 0) return 0;

            library->resolvedPath = actual;
            library->bias = info->dlpi_addr;
            library->found = true;
            return 1;
        }, &library);

        bias = library.bias;
        loadedPath = library.resolvedPath;
        return library.found;
    }

    using FunctionSetter = std::function<void(uintptr_t)>;

    template <typename T>
    std::pair<std::string, FunctionSetter> function(const char* name, T& target) {
        return { name, [&target](uintptr_t address) { target = reinterpret_cast<T>(address); } };
    }

    // The qualified name of a demangled function, e.g. `ns::Class::method` from `ns::Class::method(int) const`
    std::string qualifiedName(const char* demangled) {
        const char* parameters = strchr(demangled, '(');
        return parameters ? std::string(demangled, parameters - demangled) : demangled;
    }

    // Finds functions by their qualified names, by demangling the library's dynamic symbol table read from disk,
    // as the linker won't resolve symbols across namespaces. Overloaded functions are refused, as their signatures are unknown.
    bool resolveFunctions(const char* path, const std::vector<std::pair<std::string, FunctionSetter>>& functions) {
        uintptr_t bias;
        std::string loadedPath;
        if (!findLoadedLibrary(path, bias, loadedPath)) {
            LOGE("%s is not loaded", path);
            return false;
        }

        int fd = open(loadedPath.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0) return false;

        struct stat st {};
        fstat(fd, &st);
        auto* base = static_cast<const uint8_t*>(mmap(nullptr, st.st_size, PROT_READ, MAP_PRIVATE, fd, 0));
        close(fd);
        if (base == MAP_FAILED) return false;

        struct Match {
            std::string signature;
            uintptr_t address = 0;
            bool overloaded = false;
        };
        std::unordered_map<std::string, Match> matches;
        for (auto& [name, setter] : functions) matches.emplace(name, Match {});

        auto* header = reinterpret_cast<const ElfW(Ehdr)*>(base);
        auto* sections = reinterpret_cast<const ElfW(Shdr)*>(base + header->e_shoff);

        for (int i = 0; i < header->e_shnum; i++) {
            if (sections[i].sh_type != SHT_DYNSYM) continue;

            auto* entries = reinterpret_cast<const ElfW(Sym)*>(base + sections[i].sh_offset);
            auto* strings = reinterpret_cast<const char*>(base + sections[sections[i].sh_link].sh_offset);
            size_t count = sections[i].sh_size / sizeof(ElfW(Sym));

            for (size_t j = 0; j < count; j++) {
                if (entries[j].st_shndx == SHN_UNDEF || ELF_ST_TYPE(entries[j].st_info) != STT_FUNC) continue;

                int status;
                char* demangled = abi::__cxa_demangle(strings + entries[j].st_name, nullptr, nullptr, &status);
                if (status != 0) continue;

                auto it = matches.find(qualifiedName(demangled));
                if (it != matches.end()) {
                    auto& match = it->second;
                    // Constructors and destructors have multiple symbols (e.g. complete and base object), which demangle the same
                    if (match.address == 0) {
                        match.signature = demangled;
                        match.address = bias + entries[j].st_value;
                    } else if (match.signature != demangled) {
                        match.overloaded = true;
                    }
                }

                free(demangled);
            }
        }

        munmap(const_cast<uint8_t*>(base), st.st_size);

        bool resolved = true;
        for (auto& [name, setter] : functions) {
            auto& match = matches.at(name);
            if (match.address == 0) {
                LOGE("Could not find %s", name.c_str());
                resolved = false;
            } else if (match.overloaded) {
                LOGE("%s is overloaded, cannot tell which one to use", name.c_str());
                resolved = false;
            } else {
                LOGI("Found %s", match.signature.c_str());
                setter(match.address);
            }
        }

        return resolved;
    }

    bool resolveHermesCDP(const char* hermesPath) {
        return resolveFunctions(hermesPath, {
            function("facebook::hermes::cdp::CDPDebugAPI::create", gCdp.createDebugAPI),
            function("facebook::hermes::cdp::CDPDebugAPI::addConsoleMessage", gCdp.addConsoleMessage),
            function("facebook::hermes::cdp::CDPAgent::create", gCdp.createAgent),
            function("facebook::hermes::cdp::CDPAgent::handleCommand", gCdp.handleCommand),
            function("facebook::hermes::cdp::CDPAgent::~CDPAgent", gCdp.destroyAgent),
            function("facebook::hermes::debugger::Debugger::captureStackTrace", gCdp.captureStackTrace),
        });
    }

    JNIEnv* getEnv() {
        JNIEnv* env = nullptr;
        if (gVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) return env;

        // Callbacks can come from threads Hermes creates, which are detached again when they exit
        gVm->AttachCurrentThread(&env, nullptr);
        pthread_setspecific(gDetachKey, env);
        return env;
    }

    jbyteArray toByteArray(JNIEnv* env, const std::string& string) {
        jbyteArray bytes = env->NewByteArray(static_cast<jsize>(string.size()));
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(string.size()), reinterpret_cast<const jbyte*>(string.data()));
        return bytes;
    }

    std::string fromByteArray(JNIEnv* env, jbyteArray bytes) {
        jsize length = env->GetArrayLength(bytes);
        std::string string(static_cast<size_t>(length), '\0');
        env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte*>(string.data()));
        return string;
    }

    void sendToJava(const std::string& message) {
        JNIEnv* env = getEnv();
        jbyteArray bytes = toByteArray(env, message);
        env->CallStaticVoidMethod(gClass, gOnMessage, bytes);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        env->DeleteLocalRef(bytes);
    }

    void destroyAgentLocked() {
        if (!gAgent) return;
        gCdp.destroyAgent(gAgent);
        ::operator delete(gAgent);
        gAgent = nullptr;
    }

    void installConsole(jsi::Runtime& runtime) {
        auto report = jsi::Function::createFromHostFunction(
            runtime,
            jsi::PropNameID::forAscii(runtime, "__covalentDevToolsConsole"),
            1,
            [](jsi::Runtime& runtime, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1 || !args[0].isNumber()) return jsi::Value::undefined();

                std::vector<jsi::Value> values;
                values.reserve(count - 1);
                for (size_t i = 1; i < count; i++) values.emplace_back(runtime, args[i]);

                double timestamp = std::chrono::duration<double, std::milli>(
                    std::chrono::system_clock::now().time_since_epoch()).count();
                auto type = static_cast<facebook::hermes::cdp::ConsoleAPIType>(static_cast<int>(args[0].getNumber()));

                gCdp.addConsoleMessage(gDebugAPI, facebook::hermes::cdp::ConsoleMessage(
                    timestamp, type, std::move(values), gCdp.captureStackTrace(&gHermes->getDebugger())));
                return jsi::Value::undefined();
            });

        runtime.global().setProperty(runtime, "__covalentDevToolsConsole", report);
        // See app/src/main/ts/devtools-console.ts
        runtime.evaluateJavaScript(std::make_shared<jsi::StringBuffer>(gConsoleScript), "covalent://assets/devtools-console.bundle");
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_palmdevs_covalent_tweaks_HermesDevTools_nativeInstall(JNIEnv* env, jclass clazz, jlong executorPointer, jstring hermesPath, jbyteArray consoleScript) {
    if (gDebugAPI) return JNI_TRUE;

    env->GetJavaVM(&gVm);
    gClass = static_cast<jclass>(env->NewGlobalRef(clazz));
    gOnMessage = env->GetStaticMethodID(clazz, "onMessage", "([B)V");
    gOnReady = env->GetStaticMethodID(clazz, "onReady", "()V");
    gOnBindingCalled = env->GetStaticMethodID(clazz, "onBindingCalled", "([B[B)V");

    // A pending exception would make the RuntimeExecutor throw
    if (!gOnMessage || !gOnReady || !gOnBindingCalled) {
        env->ExceptionClear();
        LOGE("Could not find callbacks, is the Kotlin side in sync?");
        return JNI_FALSE;
    }
    pthread_key_create(&gDetachKey, [](void*) { gVm->DetachCurrentThread(); });

    const char* path = env->GetStringUTFChars(hermesPath, nullptr);
    bool resolved = resolveHermesCDP(path);
    env->ReleaseStringUTFChars(hermesPath, path);
    if (!resolved) return JNI_FALSE;

    gConsoleScript = fromByteArray(env, consoleScript);
    gExecutor = reinterpret_cast<react::JRuntimeExecutor*>(executorPointer)->get();
    gExecutor([](jsi::Runtime& runtime) {
        auto* ihermes = static_cast<facebook::hermes::IHermes*>(runtime.castInterface(facebook::hermes::IHermes::uuid));
        if (!ihermes) {
            LOGE("Runtime is not Hermes");
            return;
        }

        gHermes = ihermes;
        gRuntime = static_cast<HermesRuntime*>(ihermes);
        gDebugAPI = gCdp.createDebugAPI(*gRuntime, facebook::hermes::cdp::kMaxCachedConsoleMessages).ptr;
        LOGI("Created CDPDebugAPI");

        try {
            installConsole(*gRuntime);
        } catch (const std::exception& e) {
            LOGE("Failed to install console forwarding: %s", e.what());
        }

        JNIEnv* env = getEnv();
        env->CallStaticVoidMethod(gClass, gOnReady);
    });

    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_me_palmdevs_covalent_tweaks_HermesDevTools_nativeConnect(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(gAgentMutex);
    destroyAgentLocked();

    EnqueueRuntimeTaskFunc enqueueRuntimeTask = [](RuntimeTask task) {
        gExecutor([task = std::move(task)](jsi::Runtime&) { task(*gRuntime); });
    };

    gAgent = gCdp.createAgent(1, gDebugAPI, std::move(enqueueRuntimeTask), sendToJava, OpaqueState {}).ptr;
    LOGI("Created CDPAgent");
}

extern "C" JNIEXPORT void JNICALL
Java_me_palmdevs_covalent_tweaks_HermesDevTools_nativeDisconnect(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(gAgentMutex);
    destroyAgentLocked();
    LOGI("Destroyed CDPAgent");
}

extern "C" JNIEXPORT void JNICALL
Java_me_palmdevs_covalent_tweaks_HermesDevTools_nativeSend(JNIEnv* env, jclass, jbyteArray message) {
    std::string json = fromByteArray(env, message);

    std::lock_guard<std::mutex> lock(gAgentMutex);
    if (gAgent) gCdp.handleCommand(gAgent, std::move(json));
}

extern "C" JNIEXPORT void JNICALL
Java_me_palmdevs_covalent_tweaks_HermesDevTools_nativeAddBinding(JNIEnv* env, jclass, jbyteArray nameBytes) {
    std::string name = fromByteArray(env, nameBytes);

    gExecutor([name](jsi::Runtime&) {
        jsi::Runtime& runtime = *gRuntime;
        auto binding = jsi::Function::createFromHostFunction(
            runtime,
            jsi::PropNameID::forUtf8(runtime, name),
            1,
            [name](jsi::Runtime& runtime, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1 || !args[0].isString()) return jsi::Value::undefined();

                JNIEnv* env = getEnv();
                jbyteArray nameArray = toByteArray(env, name);
                jbyteArray payloadArray = toByteArray(env, args[0].getString(runtime).utf8(runtime));
                env->CallStaticVoidMethod(gClass, gOnBindingCalled, nameArray, payloadArray);
                if (env->ExceptionCheck()) {
                    env->ExceptionDescribe();
                    env->ExceptionClear();
                }
                env->DeleteLocalRef(nameArray);
                env->DeleteLocalRef(payloadArray);
                return jsi::Value::undefined();
            });

        runtime.global().setProperty(runtime, name.c_str(), binding);
        LOGI("Added binding %s", name.c_str());
    });
}
