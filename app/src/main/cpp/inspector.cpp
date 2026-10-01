// Native side of tap-to-select and highlighting for React DevTools, see react-devtools-inspector.ts.
//
// Exposes `__covalentElementInspectorHost` to JS so it can toggle touch interception and draw highlights,
// and forwards intercepted touches to `__covalentElementInspector.inspectAt` on the JS thread.

#include <jni.h>
#include <android/log.h>

#include <vector>

#include <jsi/jsi.h>

#include "JRuntimeExecutor.h"

#define LOG_TAG "Covalent"
#define LOGE(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "elementInspector:native - " fmt, ##__VA_ARGS__)

using namespace facebook;

namespace {
    JavaVM* gVm = nullptr;
    jclass gClass = nullptr;
    jmethodID gSetInspecting = nullptr;
    jmethodID gHighlight = nullptr;

    react::RuntimeExecutor gExecutor;

    // Host functions are only called from the JS thread, which is a Java thread
    JNIEnv* getEnv() {
        JNIEnv* env = nullptr;
        gVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        return env;
    }

    void clearException(JNIEnv* env) {
        if (!env->ExceptionCheck()) return;
        env->ExceptionDescribe();
        env->ExceptionClear();
    }

    jsi::Function hostFunction(jsi::Runtime& runtime, const char* name, jsi::HostFunctionType function) {
        return jsi::Function::createFromHostFunction(runtime, jsi::PropNameID::forAscii(runtime, name), 1, std::move(function));
    }

    void installHost(jsi::Runtime& runtime) {
        jsi::Object host(runtime);

        host.setProperty(runtime, "setInspecting", hostFunction(runtime, "setInspecting",
            [](jsi::Runtime&, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                bool inspecting = count > 0 && args[0].isBool() && args[0].getBool();

                JNIEnv* env = getEnv();
                env->CallStaticVoidMethod(gClass, gSetInspecting, static_cast<jboolean>(inspecting));
                clearException(env);
                return jsi::Value::undefined();
            }));

        // Takes a flat array of window rectangles (x, y, width, height, ...) in dp, or null to hide
        host.setProperty(runtime, "highlight", hostFunction(runtime, "highlight",
            [](jsi::Runtime& runtime, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                JNIEnv* env = getEnv();
                jfloatArray rects = nullptr;

                if (count > 0 && args[0].isObject() && args[0].getObject(runtime).isArray(runtime)) {
                    auto array = args[0].getObject(runtime).getArray(runtime);
                    size_t size = array.size(runtime);

                    std::vector<jfloat> values(size);
                    for (size_t i = 0; i < size; i++) {
                        auto value = array.getValueAtIndex(runtime, i);
                        values[i] = value.isNumber() ? static_cast<jfloat>(value.getNumber()) : 0;
                    }

                    rects = env->NewFloatArray(static_cast<jsize>(size));
                    env->SetFloatArrayRegion(rects, 0, static_cast<jsize>(size), values.data());
                }

                env->CallStaticVoidMethod(gClass, gHighlight, rects);
                clearException(env);
                if (rects) env->DeleteLocalRef(rects);
                return jsi::Value::undefined();
            }));

        runtime.global().setProperty(runtime, "__covalentElementInspectorHost", host);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_me_palmdevs_covalent_tweaks_ElementInspector_nativeInstall(JNIEnv* env, jclass clazz, jlong executorPointer) {
    if (!gClass) {
        gSetInspecting = env->GetStaticMethodID(clazz, "setInspecting", "(Z)V");
        gHighlight = env->GetStaticMethodID(clazz, "highlight", "([F)V");

        // A pending exception would make the RuntimeExecutor throw
        if (!gSetInspecting || !gHighlight) {
            env->ExceptionClear();
            LOGE("Could not find callbacks, is the Kotlin side in sync?");
            return;
        }

        env->GetJavaVM(&gVm);
        gClass = static_cast<jclass>(env->NewGlobalRef(clazz));
    }

    gExecutor = reinterpret_cast<react::JRuntimeExecutor*>(executorPointer)->get();
    gExecutor([](jsi::Runtime& runtime) {
        try {
            installHost(runtime);
        } catch (const std::exception& e) {
            LOGE("Failed to install: %s", e.what());
        }
    });
}

extern "C" JNIEXPORT void JNICALL
Java_me_palmdevs_covalent_tweaks_ElementInspector_nativeInspectAt(JNIEnv*, jclass, jfloat x, jfloat y, jboolean done) {
    if (!gExecutor) return;

    gExecutor([x, y, done](jsi::Runtime& runtime) {
        try {
            auto inspector = runtime.global().getProperty(runtime, "__covalentElementInspector");
            if (!inspector.isObject()) return;

            inspector.getObject(runtime)
                .getPropertyAsFunction(runtime, "inspectAt")
                .call(runtime, static_cast<double>(x), static_cast<double>(y), static_cast<bool>(done));
        } catch (const std::exception& e) {
            LOGE("Failed to inspect: %s", e.what());
        }
    });
}
