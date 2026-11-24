#pragma once
#include <jni.h>

#include <cstdint>
#include <stdexcept>
#include <string>
#include <vector>

namespace motexa::cast::jni {
inline std::string fromJava(JNIEnv* env, jstring value) {
  if (!value) return "";
  jsize n = env->GetStringLength(value);
  if (n > 512 * 1024) throw std::invalid_argument("jni_string_too_large");
  const jchar* input = env->GetStringChars(value, nullptr);
  if (!input) throw std::runtime_error("native_string_unavailable");
  std::string result;
  for (jsize i = 0; i < n; i++) {
    uint32_t code = input[i];
    if (code >= 0xd800 && code <= 0xdbff && i + 1 < n &&
        input[i + 1] >= 0xdc00 && input[i + 1] <= 0xdfff)
      code = 0x10000 + ((code - 0xd800) << 10) + (input[++i] - 0xdc00);
    else if (code >= 0xd800 && code <= 0xdfff)
      code = 0xfffd;
    if (code < 0x80)
      result.push_back(char(code));
    else if (code < 0x800) {
      result.push_back(char(0xc0 | code >> 6));
      result.push_back(char(0x80 | (code & 63)));
    } else if (code < 0x10000) {
      result.push_back(char(0xe0 | code >> 12));
      result.push_back(char(0x80 | ((code >> 6) & 63)));
      result.push_back(char(0x80 | (code & 63)));
    } else {
      result.push_back(char(0xf0 | code >> 18));
      result.push_back(char(0x80 | ((code >> 12) & 63)));
      result.push_back(char(0x80 | ((code >> 6) & 63)));
      result.push_back(char(0x80 | (code & 63)));
    }
  }
  env->ReleaseStringChars(value, input);
  return result;
}
inline jstring toJava(JNIEnv* env, const std::string& value) {
  std::vector<jchar> chars;
  for (size_t i = 0; i < value.size();) {
    uint8_t first = value[i++];
    uint32_t code = first;
    int remaining = first < 0x80 ? 0 : first < 0xe0 ? 1 : first < 0xf0 ? 2 : 3;
    if (remaining) {
      code = first & ((1u << (6 - remaining)) - 1u);
      for (int j = 0; j < remaining && i < value.size(); j++)
        code = (code << 6) | (uint8_t(value[i++]) & 63);
    }
    if (code > 0xffff) {
      code -= 0x10000;
      chars.push_back(jchar(0xd800 + (code >> 10)));
      chars.push_back(jchar(0xdc00 + (code & 1023)));
    } else
      chars.push_back(jchar(code));
  }
  return env->NewString(chars.data(), jsize(chars.size()));
}
}  // namespace motexa::cast::jni
