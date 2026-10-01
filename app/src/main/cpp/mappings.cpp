// Makes bundles loaded from APK assets patchable by the Hermes debugger.
//
// React Native runs bytecode bundles straight from the asset's memory map (AAsset_getBuffer),which is a read-only MAP_SHARED mapping of the APK.
// Hermes installs breakpoints (including the ones used for stepping) by patching the bytecode in place, and makes it writable with mprotect first.
// That fails for shared mappings of files opened read-only, and Hermes aborts with "mprotect failed before modifying breakpoint".
//
// This remaps the same file range as MAP_PRIVATE keeps the contents and address, but lets mprotect succeed, with writes going to copy-on-write pages instead of the file.

#include <jni.h>
#include <android/log.h>

#include <fcntl.h>
#include <sys/mman.h>
#include <unistd.h>

#include <cerrno>
#include <cinttypes>
#include <cstdio>
#include <cstring>
#include <string>
#include <unordered_set>

#define LOG_TAG "Covalent"
#define LOGE(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "enableReactNativeDevTools:native - " fmt, ##__VA_ARGS__)

extern "C" JNIEXPORT jint JNICALL
Java_me_palmdevs_covalent_tweaks_AssetMappings_nativePrivatize(JNIEnv* env, jclass, jobjectArray paths) {
    std::unordered_set<std::string> files;
    for (jsize i = 0, count = env->GetArrayLength(paths); i < count; i++) {
        auto path = static_cast<jstring>(env->GetObjectArrayElement(paths, i));
        const char* chars = env->GetStringUTFChars(path, nullptr);
        files.emplace(chars);
        env->ReleaseStringUTFChars(path, chars);
        env->DeleteLocalRef(path);
    }

    FILE* maps = fopen("/proc/self/maps", "re");
    if (!maps) {
        LOGE("Could not open /proc/self/maps: %s", strerror(errno));
        return 0;
    }

    int remapped = 0;
    char line[4096];

    while (fgets(line, sizeof(line), maps)) {
        uintptr_t start, end;
        char perms[5];
        uint64_t offset;
        int pathStart = 0;

        if (sscanf(line, "%" SCNxPTR "-%" SCNxPTR " %4s %" SCNx64 " %*s %*s %n", &start, &end, perms, &offset, &pathStart) < 4 || !pathStart)
            continue;

        // Only read-only shared mappings are affected
        if (perms[1] != '-' || perms[3] != 's') continue;

        std::string path(line + pathStart);
        if (!path.empty() && path.back() == '\n') path.pop_back();
        if (!files.count(path)) continue;

        int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0) {
            LOGE("Could not open %s: %s", path.c_str(), strerror(errno));
            continue;
        }

        int prot = (perms[0] == 'r' ? PROT_READ : 0) | (perms[2] == 'x' ? PROT_EXEC : 0);

        // MAP_FIXED replaces the old mapping atomically, so concurrent readers never see it unmapped
        void* result = mmap(reinterpret_cast<void*>(start), end - start, prot, MAP_PRIVATE | MAP_FIXED, fd, static_cast<off_t>(offset));
        close(fd);

        if (result == MAP_FAILED) {
            LOGE("Could not remap %" PRIxPTR "-%" PRIxPTR " of %s: %s", start, end, path.c_str(), strerror(errno));
            continue;
        }

        remapped++;
    }

    fclose(maps);
    return remapped;
}
