#include <fbjni/fbjni.h>
#include <ReactCommon/RuntimeExecutor.h>
#include <jsi/jsi.h>
#include <android/log.h>

#define LOG_TAG "Covalent"
#define LOGI(fmt, ...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, "injectJSI:native - " fmt, ##__VA_ARGS__)
#define LOGE(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "injectJSI:native - " fmt, ##__VA_ARGS__)

using namespace facebook;
using namespace facebook::jni;
using namespace facebook::react;

namespace facebook::react {
    class JRuntimeExecutor : public jni::HybridClass<JRuntimeExecutor> {
    public:
        RuntimeExecutor runtimeExecutor_;
        inline RuntimeExecutor get() { return runtimeExecutor_; }
    };
}

void injectJSI(alias_ref<jclass>, jlong nativePointer) {
    LOGI("Called with addr: %llx", (long long)nativePointer);

    if (!nativePointer) {
        LOGE("nullptr received...");
        return;
    }

    try {
        auto* runtimeExecutorObj = reinterpret_cast<JRuntimeExecutor*>(nativePointer);
        auto executor = runtimeExecutorObj->get();

        LOGI("Got the executor!");

        executor([](jsi::Runtime& runtime) {
            LOGI("Got the runtime!");
            runtime.global().setProperty(runtime, "__COVALENT__",
                jsi::String::createFromUtf8(runtime, "Covalent was here!"));
        });

    } catch (const std::exception& e) {
        LOGE("error: %s", e.what());
    }
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    return initialize(vm, [] {
        registerNatives("me/palmdevs/covalent/tweaks/JSIInjector", {
            makeNativeMethod("injectJSI", injectJSI),
        });
    });
}