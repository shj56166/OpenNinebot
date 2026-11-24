LOCAL_PATH := $(call my-dir)
CAST_DEPS := $(CAST_DEPS_DIR)
CAST_ROOT := $(abspath $(LOCAL_PATH)/../../../../modules/ninebot-cast-android)
MPEG2_DEPS := $(CAST_DEPS)/mpeg2-arm64
include $(CLEAR_VARS)
LOCAL_MODULE := cast_avcodec
LOCAL_SRC_FILES := $(MPEG2_DEPS)/lib/libavcodec.a
include $(PREBUILT_STATIC_LIBRARY)
include $(CLEAR_VARS)
LOCAL_MODULE := cast_swscale
LOCAL_SRC_FILES := $(MPEG2_DEPS)/lib/libswscale.a
include $(PREBUILT_STATIC_LIBRARY)
include $(CLEAR_VARS)
LOCAL_MODULE := cast_avutil
LOCAL_SRC_FILES := $(MPEG2_DEPS)/lib/libavutil.a
include $(PREBUILT_STATIC_LIBRARY)
include $(CLEAR_VARS)
LOCAL_MODULE := cast_crypto
LOCAL_C_INCLUDES := $(CAST_DEPS)/mbedtls/include
LOCAL_SRC_FILES := $(wildcard $(CAST_DEPS)/mbedtls/library/*.c)
LOCAL_CFLAGS := -O2 -fvisibility=hidden
include $(BUILD_STATIC_LIBRARY)
include $(CLEAR_VARS)
LOCAL_MODULE := ninebot_cast
LOCAL_SRC_FILES := $(CAST_DEPS)/cjson/cJSON.c
VEHICLE_CAST_ROOT := $(CAST_ROOT)
VEHICLE_CAST_DEPS := $(CAST_DEPS)
include $(CAST_ROOT)/native-core.mk
LOCAL_STATIC_LIBRARIES := cast_avcodec cast_swscale cast_avutil cast_crypto
LOCAL_LDLIBS := -llog -lm -lz
LOCAL_LDFLAGS += -Wl,--exclude-libs,ALL
include $(BUILD_SHARED_LIBRARY)
