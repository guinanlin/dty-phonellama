#include <jni.h>

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

#include <android/log.h>
#ifdef PHONELLAMA_ENABLE_VULKAN
#include <ggml-vulkan.h>
#endif
#include <fcntl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

extern int phonellama_funasr_main(int argc, char **argv);

namespace {

constexpr char kTag[] = "PhoneLlamaASR";

std::string runSenseVoice(
    const std::string &modelPath,
    const std::string &vadPath,
    const std::string &audioPath,
    const std::string &accelerator) {
    int outputPipe[2];
    if (pipe(outputPipe) != 0) {
        throw std::runtime_error("cannot create ASR output pipe");
    }

    const int savedStdout = dup(STDOUT_FILENO);
    if (savedStdout < 0 || dup2(outputPipe[1], STDOUT_FILENO) < 0) {
        close(outputPipe[0]);
        close(outputPipe[1]);
        throw std::runtime_error("cannot redirect ASR output");
    }
    close(outputPipe[1]);

    std::vector<std::string> args = {
        "llama-funasr-sensevoice",
        "-m", modelPath,
        "--vad", vadPath,
        "-a", audioPath,
#ifdef PHONELLAMA_ENABLE_VULKAN
        "--backend", accelerator,
#endif
        "--keep-tags",
    };
#ifndef PHONELLAMA_ENABLE_VULKAN
    (void) accelerator;
#endif
    std::vector<char *> argv;
    argv.reserve(args.size() + 1);
    for (auto &arg : args) {
        argv.push_back(arg.data());
    }
    argv.push_back(nullptr);

    int result = phonellama_funasr_main(
        static_cast<int>(args.size()), argv.data());
    fflush(stdout);
    dup2(savedStdout, STDOUT_FILENO);
    close(savedStdout);

    std::string output;
    char buffer[4096];
    ssize_t count;
    while ((count = read(outputPipe[0], buffer, sizeof(buffer))) > 0) {
        output.append(buffer, static_cast<size_t>(count));
    }
    close(outputPipe[0]);

    if (result != 0) {
        __android_log_print(
            ANDROID_LOG_ERROR, kTag, "SenseVoice returned %d", result);
        throw std::runtime_error("SenseVoice inference failed");
    }
    return output;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_google_ai_edge_gallery_runtime_asr_SenseVoiceNative_nativeTranscribe(
    JNIEnv *env,
    jobject /* thiz */,
    jstring modelPath,
    jstring vadPath,
    jstring audioPath,
    jstring accelerator) {
    if (modelPath == nullptr || vadPath == nullptr || audioPath == nullptr ||
        accelerator == nullptr) {
        return nullptr;
    }

    const char *modelChars = env->GetStringUTFChars(modelPath, nullptr);
    const char *vadChars = env->GetStringUTFChars(vadPath, nullptr);
    const char *audioChars = env->GetStringUTFChars(audioPath, nullptr);
    const char *acceleratorChars = env->GetStringUTFChars(accelerator, nullptr);
    if (modelChars == nullptr || vadChars == nullptr || audioChars == nullptr ||
        acceleratorChars == nullptr) {
        if (modelChars != nullptr) env->ReleaseStringUTFChars(modelPath, modelChars);
        if (vadChars != nullptr) env->ReleaseStringUTFChars(vadPath, vadChars);
        if (audioChars != nullptr) env->ReleaseStringUTFChars(audioPath, audioChars);
        if (acceleratorChars != nullptr) {
            env->ReleaseStringUTFChars(accelerator, acceleratorChars);
        }
        return nullptr;
    }

    try {
        const std::string output =
            runSenseVoice(modelChars, vadChars, audioChars, acceleratorChars);
        env->ReleaseStringUTFChars(modelPath, modelChars);
        env->ReleaseStringUTFChars(vadPath, vadChars);
        env->ReleaseStringUTFChars(audioPath, audioChars);
        env->ReleaseStringUTFChars(accelerator, acceleratorChars);
        return env->NewStringUTF(output.c_str());
    } catch (const std::exception &error) {
        __android_log_print(
            ANDROID_LOG_ERROR, kTag, "JNI inference error: %s", error.what());
        env->ReleaseStringUTFChars(modelPath, modelChars);
        env->ReleaseStringUTFChars(vadPath, vadChars);
        env->ReleaseStringUTFChars(audioPath, audioChars);
        env->ReleaseStringUTFChars(accelerator, acceleratorChars);
        return nullptr;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_google_ai_edge_gallery_runtime_asr_SenseVoiceNative_nativeHasGpuBackend(
    JNIEnv * /* env */,
    jobject /* thiz */) {
#ifdef PHONELLAMA_ENABLE_VULKAN
    return ggml_backend_vk_get_device_count() > 0 ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}
