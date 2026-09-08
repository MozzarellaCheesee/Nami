# Трекерная (.mod/.xm/.it/.s3m) и игровая (.nsf/.spc/.vgm/.gbs) музыка.
#
# Готовых опубликованных Android-обёрток ни для libopenmpt, ни для game-music-emu на Maven
# Central / JitPack нет (проверено), поэтому обе собираются из исходников. Обе апстрим-библиотеки
# сами поставляют официальный Android.mk, так что ndk-build - самый дешёвый путь: не нужен ни
# CMake, ни cargo-ndk, ndk-build лежит прямо в NDK.
#
# Исходники качает и распаковывает :core:tracker (см. его build.gradle.kts) в native/third_party,
# в git они не лежат - ровно как собранные cargo-ndk .so в core/native/src/main/jniLibs.

LOCAL_PATH := $(call my-dir)
NAMI_THIRD_PARTY := $(LOCAL_PATH)/../third_party
NAMI_OPENMPT := $(NAMI_THIRD_PARTY)/libopenmpt
NAMI_GME := $(NAMI_THIRD_PARTY)/game-music-emu

include $(CLEAR_VARS)
LOCAL_MODULE := namitracker
LOCAL_SRC_FILES := nami_tracker_jni.cpp
LOCAL_C_INCLUDES := $(NAMI_OPENMPT) $(NAMI_GME)
LOCAL_CPPFLAGS := -std=c++17 -fvisibility=hidden
LOCAL_SHARED_LIBRARIES := openmpt libgme
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)

# APE/WavPack/TAK/Musepack - минимальный FFmpeg, собранный native/jni/build_ffmpeg.sh.
# Статические .a, а не .so: с четырьмя декодерами и пятью демуксерами линковка отбрасывает
# почти всё, и получается ~1.5 МБ на ABI вместо четырёх отдельных библиотек.
NAMI_FFMPEG := $(NAMI_THIRD_PARTY)/ffmpeg-build/$(TARGET_ARCH_ABI)

ifneq ($(wildcard $(NAMI_FFMPEG)/lib/libavcodec.a),)

define nami_ffmpeg_prebuilt
include $(CLEAR_VARS)
LOCAL_MODULE := $(1)
LOCAL_SRC_FILES := $(NAMI_FFMPEG)/lib/lib$(1).a
LOCAL_EXPORT_C_INCLUDES := $(NAMI_FFMPEG)/include
include $(PREBUILT_STATIC_LIBRARY)
endef

$(eval $(call nami_ffmpeg_prebuilt,avformat))
$(eval $(call nami_ffmpeg_prebuilt,avcodec))
$(eval $(call nami_ffmpeg_prebuilt,swresample))
$(eval $(call nami_ffmpeg_prebuilt,avutil))

include $(CLEAR_VARS)
LOCAL_MODULE := namiffmpeg
LOCAL_SRC_FILES := nami_ffmpeg_jni.cpp
LOCAL_CPPFLAGS := -std=c++17 -fvisibility=hidden
LOCAL_STATIC_LIBRARIES := avformat avcodec swresample avutil
LOCAL_LDLIBS := -llog -lz
include $(BUILD_SHARED_LIBRARY)

endif

# Официальные сборочные файлы апстрима, скопированные в корень дерева исходников
# (так предписывает libopenmpt/build/android_ndk/README.AndroidNDK.txt).
include $(NAMI_OPENMPT)/Android.mk
include $(NAMI_GME)/Android.mk
