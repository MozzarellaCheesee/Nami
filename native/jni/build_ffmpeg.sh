#!/bin/sh
# Сборка минимального FFmpeg под Android для APE/WavPack/TAK/Musepack.
#
# Официальное media3-decoder-ffmpeg тут не подходит принципиально, а не только потому, что его не
# публикуют бинарём: оно даёт лишь ДЕКОДЕР, а разбирать контейнер всё равно должен Extractor из
# Media3, а его для .ape/.wv/.tak/.mpc не существует. Поэтому берём из FFmpeg и демуксер, и
# декодер, и рендерим в .wav при импорте - тем же приёмом, что DSD и трекерные модули.
#
# Готового опубликованного артефакта с этими кодеками нет: единственная известная сборка
# media3-decoder-ffmpeg на Maven Central (org.jellyfin.media3) включает
# flac alac pcm_mulaw pcm_alaw mp3 aac ac3 eac3 dca mlp truehd - ни одного из нужных четырёх.
#
# Запуск: native/jni/build_ffmpeg.sh (Git Bash), исходники берутся из native/third_party/ffmpeg.
# Результат - статические .a в native/third_party/ffmpeg-build/<abi>, их подхватывает Android.mk.
set -e

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
THIRD_PARTY="$SCRIPT_DIR/../third_party"
FFMPEG_SRC="$THIRD_PARTY/ffmpeg"
OUT_ROOT="$THIRD_PARTY/ffmpeg-build"
# Путь обязательно в msys-форме (/c/...): компилятор запускает msys-овский sh, виндовый
# C:\... он не разберёт.
NDK="${ANDROID_NDK_HOME:-$HOME/AppData/Local/Android/Sdk/ndk/30.0.15729638}"
NDK=$(cygpath -u "$NDK" 2>/dev/null || echo "$NDK")
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64"
API=26

# mpc7/mpc8 - две несовместимые версии Musepack (SV7 и SV8), нужны обе.
DECODERS="ape,wavpack,tak,mpc7,mpc8"
DEMUXERS="ape,wv,tak,mpc,mpc8"

build_abi() {
    abi=$1
    arch=$2
    triple=$3
    extra_cflags=$4

    out="$OUT_ROOT/$abi"
    # Собираем прямо в дереве исходников, а не рядом: make здесь - обычный виндовый (из NDK или
    # MinGW), он не понимает msys-путей вида /c/..., которые configure подставляет в SRC_PATH при
    # сборке out-of-tree. In-tree SRC_PATH остаётся относительным, и всё сходится.
    cd "$FFMPEG_SRC"
    make distclean >/dev/null 2>&1 || true
    # --disable-asm ниже: под Git Bash .S-файлы проходят препроцессор отдельным шагом, и макросы
    # из asm.S до ассемблера не доезжают. Потеря невелика - декодируем разово при импорте, а не
    # в реальном времени.

    ./configure \
        --prefix="$out" \
        --enable-cross-compile \
        --target-os=android \
        --arch="$arch" \
        --sysroot="$TOOLCHAIN/sysroot" \
        --cc="$TOOLCHAIN/bin/clang" \
        --cxx="$TOOLCHAIN/bin/clang++" \
        --ar="$TOOLCHAIN/bin/llvm-ar" \
        --nm="$TOOLCHAIN/bin/llvm-nm" \
        --ranlib="$TOOLCHAIN/bin/llvm-ranlib" \
        --strip="$TOOLCHAIN/bin/llvm-strip" \
        --extra-cflags="--target=$triple$API -fPIC -O2 $extra_cflags" \
        --extra-ldflags="--target=$triple$API" \
        --disable-everything \
        --disable-asm \
        --disable-vulkan \
        --disable-programs \
        --disable-doc \
        --disable-avdevice \
        --disable-avfilter \
        --disable-swscale \
        --disable-postproc \
        --disable-network \
        --disable-symver \
        --disable-shared \
        --enable-static \
        --enable-pic \
        --enable-protocol=file \
        --enable-decoder="$DECODERS" \
        --enable-demuxer="$DEMUXERS" \
        --enable-parser=tak

    make -j"$(nproc)"
    make install
}

build_abi arm64-v8a aarch64 aarch64-linux-android ""
build_abi armeabi-v7a arm armv7a-linux-androideabi "-mfpu=neon -mfloat-abi=softfp"

echo "FFmpeg собран в $OUT_ROOT"
