#pragma once

#include <fbjni/fbjni.h>
#include <ReactCommon/RuntimeExecutor.h>

namespace facebook::react {
    // Layout-compatible with React Native's JRuntimeExecutor, so the native pointer of a Java RuntimeExecutor can be read.
    class JRuntimeExecutor : public jni::HybridClass<JRuntimeExecutor> {
    public:
        RuntimeExecutor runtimeExecutor_;
        inline RuntimeExecutor get() { return runtimeExecutor_; }
    };
}
