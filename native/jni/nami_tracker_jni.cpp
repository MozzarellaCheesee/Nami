// JNI-обвязка над libopenmpt (трекерные модули) и game-music-emu (чиптюны консолей).
//
// Обе рендерят в один и тот же .wav, а не в поток: в проекте это уже устоявшийся приём для
// нестандартных форматов (см. DsfToDopWav - "конвертируем один раз при импорте и отдаём обычному
// WAV-экстрактору Media3"). Свой Renderer/MediaSource для двух экзотических семейств форматов
// стоил бы кратно дороже и всю остальную обвязку (DSP, кроссфейд, ReplayGain, волна) пришлось бы
// учить работать с новым источником.
//
// Цена та же, что у DSD-пути: файл распухает до размера несжатого PCM и рендер идёт разом, а не
// по мере воспроизведения.

#include <jni.h>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <vector>

#include <libopenmpt/libopenmpt.h>
#include <gme/gme.h>

namespace {

constexpr int32_t kSampleRate = 48000;
constexpr int kChannels = 2;
// Зацикленные модули и чиптюны без явной длины играют бесконечно - без потолка рендер съел бы
// всё свободное место. 10 минут с запасом покрывают почти всё, что реально встречается.
constexpr int64_t kMaxFrames = static_cast<int64_t>(kSampleRate) * 60 * 10;
constexpr size_t kChunkFrames = 4096;

void writeLe32(uint8_t *p, uint32_t v) {
    p[0] = static_cast<uint8_t>(v);
    p[1] = static_cast<uint8_t>(v >> 8);
    p[2] = static_cast<uint8_t>(v >> 16);
    p[3] = static_cast<uint8_t>(v >> 24);
}

void writeLe16(uint8_t *p, uint16_t v) {
    p[0] = static_cast<uint8_t>(v);
    p[1] = static_cast<uint8_t>(v >> 8);
}

// Заголовок пишется дважды: сначала с нулевыми размерами, в конце - поверх, когда стало известно
// сколько кадров реально отрендерилось (заранее это неизвестно ни для модуля, ни для чиптюна).
void writeWavHeader(FILE *f, int64_t frames) {
    const uint32_t dataSize = static_cast<uint32_t>(frames * kChannels * 2);
    uint8_t h[44];
    memcpy(h, "RIFF", 4);
    writeLe32(h + 4, 36 + dataSize);
    memcpy(h + 8, "WAVEfmt ", 8);
    writeLe32(h + 16, 16);
    writeLe16(h + 20, 1); // PCM
    writeLe16(h + 22, kChannels);
    writeLe32(h + 24, kSampleRate);
    writeLe32(h + 28, kSampleRate * kChannels * 2);
    writeLe16(h + 32, kChannels * 2);
    writeLe16(h + 34, 16);
    memcpy(h + 36, "data", 4);
    writeLe32(h + 40, dataSize);
    fwrite(h, 1, sizeof(h), f);
}

bool readWholeFile(const char *path, std::vector<uint8_t> &out) {
    FILE *f = fopen(path, "rb");
    if (!f) return false;
    fseek(f, 0, SEEK_END);
    const long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (size <= 0) {
        fclose(f);
        return false;
    }
    out.resize(static_cast<size_t>(size));
    const size_t read = fread(out.data(), 1, out.size(), f);
    fclose(f);
    return read == out.size();
}

bool renderOpenmpt(const std::vector<uint8_t> &input, FILE *out, int64_t &framesWritten) {
    openmpt_module *mod = openmpt_module_create_from_memory2(
            input.data(), input.size(),
            nullptr, nullptr, nullptr, nullptr, nullptr, nullptr, nullptr);
    if (!mod) return false;

    std::vector<int16_t> buffer(kChunkFrames * kChannels);
    while (framesWritten < kMaxFrames) {
        const size_t got = openmpt_module_read_interleaved_stereo(
                mod, kSampleRate, kChunkFrames, buffer.data());
        if (got == 0) break;
        fwrite(buffer.data(), sizeof(int16_t), got * kChannels, out);
        framesWritten += static_cast<int64_t>(got);
    }
    openmpt_module_destroy(mod);
    return framesWritten > 0;
}

bool renderGme(const char *path, FILE *out, int64_t &framesWritten) {
    Music_Emu *emu = nullptr;
    if (gme_open_file(path, &emu, kSampleRate) || !emu) return false;
    if (gme_start_track(emu, 0)) {
        gme_delete(emu);
        return false;
    }

    // У чиптюнов длины в самом файле часто нет - тогда апстрим-умолчание "150 секунд плюс
    // затухание", как в его же демо-плеере.
    int lengthMs = 150000;
    gme_info_t *info = nullptr;
    if (!gme_track_info(emu, &info, 0) && info) {
        if (info->play_length > 0) lengthMs = info->play_length;
        gme_free_info(info);
    }
    gme_set_fade(emu, lengthMs);

    const int64_t targetFrames = static_cast<int64_t>(lengthMs) * kSampleRate / 1000;
    std::vector<int16_t> buffer(kChunkFrames * kChannels);
    while (framesWritten < targetFrames && framesWritten < kMaxFrames) {
        if (gme_play(emu, static_cast<int>(kChunkFrames * kChannels), buffer.data())) break;
        fwrite(buffer.data(), sizeof(int16_t), kChunkFrames * kChannels, out);
        framesWritten += static_cast<int64_t>(kChunkFrames);
        if (gme_track_ended(emu)) break;
    }
    gme_delete(emu);
    return framesWritten > 0;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_nami_core_tracker_TrackerNative_nativeRenderToWav(
        JNIEnv *env, jclass, jstring jInputPath, jstring jOutputPath) {
    const char *inputPath = env->GetStringUTFChars(jInputPath, nullptr);
    const char *outputPath = env->GetStringUTFChars(jOutputPath, nullptr);
    bool ok = false;

    std::vector<uint8_t> input;
    if (readWholeFile(inputPath, input)) {
        FILE *out = fopen(outputPath, "wb");
        if (out) {
            writeWavHeader(out, 0);
            int64_t frames = 0;
            // Формат определяем не по расширению, а попыткой: libopenmpt и gme сами узнают свои
            // контейнеры надёжнее, чем суффикс имени файла (тот же .vgm бывает и .vgz, а
            // «.mod» встречается у совершенно посторонних файлов).
            ok = renderOpenmpt(input, out, frames);
            if (!ok) {
                fseek(out, 44, SEEK_SET);
                frames = 0;
                ok = renderGme(inputPath, out, frames);
            }
            if (ok) {
                fseek(out, 0, SEEK_SET);
                writeWavHeader(out, frames);
            }
            fclose(out);
            if (!ok) remove(outputPath);
        }
    }

    env->ReleaseStringUTFChars(jInputPath, inputPath);
    env->ReleaseStringUTFChars(jOutputPath, outputPath);
    return ok ? JNI_TRUE : JNI_FALSE;
}
