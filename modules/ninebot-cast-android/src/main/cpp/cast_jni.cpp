#include <jni.h>

#include <map>
#include <mutex>
#include <stdexcept>

#include "cast_core.h"
#include "jni_strings.h"


namespace {
std::mutex sessionsMutex;
std::map<jlong, std::unique_ptr<motexa::cast::Session>> sessions;
jlong nextHandle = 1;
std::string text(JNIEnv* env, jstring value) {
  if (!value) return "{}";
  return motexa::cast::jni::fromJava(env, value);
}
}  // namespace
extern "C" JNIEXPORT jlong JNICALL
Java_com_motexa_vehiclecast_NativeCast_create(JNIEnv* env, jclass,
                                              jstring options,
                                              jstring credentials) {
  try {
    auto session = std::make_unique<motexa::cast::Session>(
        text(env, options), text(env, credentials));
    std::lock_guard<std::mutex> guard(sessionsMutex);
    jlong handle = nextHandle++;
    sessions.emplace(handle, std::move(session));
    return handle;
  } catch (...) {
    return 0;
  }
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_motexa_vehiclecast_NativeCast_dispatch(
    JNIEnv* env, jclass, jlong handle, jstring operation, jstring arguments,
    jbyteArray data, jboolean flag, jlong value, jlong elapsed, jlong wall) {
  std::lock_guard<std::mutex> guard(sessionsMutex);
  auto found = sessions.find(handle);
  if (found == sessions.end())
    return env->NewStringUTF(
        "[{\"type\":\"error\",\"code\":\"session_handle_invalid\",\"fatal\":"
        "true}]");
  try {
    motexa::cast::Bytes bytes;
    if (data) {
      jsize n = env->GetArrayLength(data);
      if (n > 1024 * 1024) throw std::runtime_error("jni_frame_too_large");
      bytes.resize(n);
      if (n)
        env->GetByteArrayRegion(data, 0, n,
                                reinterpret_cast<jbyte*>(bytes.data()));
      if (env->ExceptionCheck()) return nullptr;
    }
    auto result =
        found->second->dispatch(text(env, operation), text(env, arguments),
                                bytes, flag, value, elapsed, wall);
    return motexa::cast::jni::toJava(env, result);
  } catch (...) {
    sessions.erase(found);
    return env->NewStringUTF(
        "[{\"type\":\"error\",\"code\":\"native_dispatch_failed\",\"fatal\":"
        "true}]");
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_motexa_vehiclecast_NativeCast_destroy(JNIEnv*, jclass, jlong handle) {
  std::lock_guard<std::mutex> guard(sessionsMutex);
  sessions.erase(handle);
}
