# Ninebot-only source list; the standalone library has no trial lease dependency.
LOCAL_SRC_FILES += $(VEHICLE_CAST_ROOT)/src/main/cpp/cast_protocol.cpp \
    $(VEHICLE_CAST_ROOT)/src/main/cpp/cast_core.cpp \
    $(VEHICLE_CAST_ROOT)/src/main/cpp/cast_jni.cpp \
    $(VEHICLE_CAST_ROOT)/src/main/cpp/ninebot_gen3.cpp \
    $(VEHICLE_CAST_ROOT)/src/main/cpp/mpeg2_codec.cpp \
    $(VEHICLE_CAST_ROOT)/src/main/cpp/vendor/kcp/ikcp.c
LOCAL_C_INCLUDES += $(VEHICLE_CAST_ROOT)/src/main/cpp $(VEHICLE_CAST_DEPS)/mbedtls/include $(VEHICLE_CAST_DEPS)/cjson $(VEHICLE_CAST_DEPS)/mpeg2-arm64/include
LOCAL_CPP_FEATURES := exceptions rtti
LOCAL_CPPFLAGS += -std=c++17 -fvisibility=hidden
LOCAL_LDFLAGS += -Wl,--exclude-libs,ALL
