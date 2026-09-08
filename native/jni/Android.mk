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

# Официальные сборочные файлы апстрима, скопированные в корень дерева исходников
# (так предписывает libopenmpt/build/android_ndk/README.AndroidNDK.txt).
include $(NAMI_OPENMPT)/Android.mk
include $(NAMI_GME)/Android.mk
