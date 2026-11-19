// 跑道助手注入层 JNI 入口。
//
// 本文件只定义 App 与注入层之间的契约，具体实现由设备侧模块提供：
//   - GNSS：替换/代理 gnss HAL，回放位置、卫星状态与 NMEA；
//   - Sensor：代理 sensors HAL，回放加速度、陀螺、计步与气压；
//   - ActivityRecognition：回放步行/跑步状态。
//
// 错误码约定：
//    0  成功
//   -1  参数错误
//   -2  未实现（当前骨架）
//   -3  通道未就绪
//   -4  素材读取失败
//   -5  注入服务断开

#include <jni.h>
#include <android/log.h>
#include <string>

#define LOG_TAG "RunwayJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Session {
    std::string path;
    long long join_offset_ms = 0;
    bool paused = false;
    bool stopped = false;
    long long emitted = 0;
};

// 骨架阶段使用静态句柄表；正式实现应改为线程安全的会话管理。
Session* g_session = nullptr;

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_runway_assistant_replay_PrivilegedReplayEngine_nativeInit(JNIEnv* env, jclass, jstring config) {
    const char* cfg = config ? env->GetStringUTFChars(config, nullptr) : "";
    LOGI("nativeInit config=%s", cfg ? cfg : "");
    if (config) env->ReleaseStringUTFChars(config, cfg);

    if (g_session != nullptr) {
        delete g_session;
    }
    g_session = new Session();
    // 句柄使用非零值表示成功；正式实现返回注入服务连接句柄。
    return reinterpret_cast<jlong>(g_session);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_runway_assistant_replay_PrivilegedReplayEngine_nativeStart(
        JNIEnv* env, jclass, jlong handle, jstring path, jlong join_offset_ms) {
    if (handle == 0 || path == nullptr) return -1;
    auto* session = reinterpret_cast<Session*>(handle);

    const char* p = env->GetStringUTFChars(path, nullptr);
    session->path = p ? p : "";
    if (p) env->ReleaseStringUTFChars(path, p);

    session->join_offset_ms = join_offset_ms;
    session->paused = false;
    session->stopped = false;
    session->emitted = 0;

    LOGE("nativeStart not implemented yet, path=%s join=%lld",
         session->path.c_str(), static_cast<long long>(join_offset_ms));
    return -2;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_runway_assistant_replay_PrivilegedReplayEngine_nativeEmit(
        JNIEnv* env, jclass, jlong handle, jstring event_json) {
    if (handle == 0 || event_json == nullptr) return -1;
    auto* session = reinterpret_cast<Session*>(handle);
    if (session->stopped) return -5;
    if (session->paused) return 0;

    // 正式实现：解析事件类型，分发到 gnss / sensor / activity 通道。
    session->emitted++;
    return -2;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_runway_assistant_replay_PrivilegedReplayEngine_nativePause(JNIEnv*, jclass, jlong handle) {
    if (handle == 0) return -1;
    reinterpret_cast<Session*>(handle)->paused = true;
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_runway_assistant_replay_PrivilegedReplayEngine_nativeResume(JNIEnv*, jclass, jlong handle) {
    if (handle == 0) return -1;
    reinterpret_cast<Session*>(handle)->paused = false;
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_runway_assistant_replay_PrivilegedReplayEngine_nativeStop(JNIEnv*, jclass, jlong handle) {
    if (handle == 0) return -1;
    auto* session = reinterpret_cast<Session*>(handle);
    session->stopped = true;
    delete session;
    if (g_session == session) g_session = nullptr;
    return 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_runway_assistant_replay_PrivilegedReplayEngine_nativeStatus(JNIEnv* env, jclass, jlong handle) {
    if (handle == 0) {
        return env->NewStringUTF(R"({"state":"idle","emitted":0})");
    }
    auto* session = reinterpret_cast<Session*>(handle);
    std::string json = R"({"state":")" + std::string(session->paused ? "paused" : "running") +
                       R"(","emitted":)" + std::to_string(session->emitted) + "}";
    return env->NewStringUTF(json.c_str());
}
