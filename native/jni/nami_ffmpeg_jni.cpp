// APE / WavPack / TAK / Musepack -> .wav через минимальный FFmpeg (см. build_ffmpeg.sh, там же
// написано, почему официальное media3-decoder-ffmpeg тут не помогает: оно даёт только декодер, а
// Extractor'а для этих контейнеров в Media3 нет).
//
// Тот же приём, что для DSD и трекерных модулей: разово конвертируем при импорте, дальше файл
// идёт обычным путём Media3. Выход всегда 16-битный PCM в исходной частоте и раскладке каналов -
// апсэмплинга и даунмикса нет, ресемплер нужен только чтобы привести planar/float форматы
// декодера к interleaved s16.

#include <jni.h>
#include <cstdio>
#include <cstdint>
#include <cstring>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libswresample/swresample.h>
#include <libavutil/opt.h>
}

namespace {

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

// Размеры известны только после декодирования, поэтому заголовок пишется дважды - пустой в
// начале и настоящий поверх него в конце.
void writeWavHeader(FILE *f, int sampleRate, int channels, int64_t frames) {
    const uint32_t dataSize = static_cast<uint32_t>(frames * channels * 2);
    uint8_t h[44];
    memcpy(h, "RIFF", 4);
    writeLe32(h + 4, 36 + dataSize);
    memcpy(h + 8, "WAVEfmt ", 8);
    writeLe32(h + 16, 16);
    writeLe16(h + 20, 1); // PCM
    writeLe16(h + 22, static_cast<uint16_t>(channels));
    writeLe32(h + 24, static_cast<uint32_t>(sampleRate));
    writeLe32(h + 28, static_cast<uint32_t>(sampleRate * channels * 2));
    writeLe16(h + 32, static_cast<uint16_t>(channels * 2));
    writeLe16(h + 34, 16);
    memcpy(h + 36, "data", 4);
    writeLe32(h + 40, dataSize);
    fwrite(h, 1, sizeof(h), f);
}

bool decodeToWav(const char *inputPath, const char *outputPath) {
    AVFormatContext *format = nullptr;
    if (avformat_open_input(&format, inputPath, nullptr, nullptr) < 0) return false;

    bool ok = false;
    AVCodecContext *codecCtx = nullptr;
    SwrContext *swr = nullptr;
    AVPacket *packet = nullptr;
    AVFrame *frame = nullptr;
    FILE *out = nullptr;
    uint8_t *outBuffer = nullptr;
    int outBufferSamples = 0;
    int64_t frames = 0;

    do {
        if (avformat_find_stream_info(format, nullptr) < 0) break;
        const AVCodec *codec = nullptr;
        const int streamIndex = av_find_best_stream(format, AVMEDIA_TYPE_AUDIO, -1, -1, &codec, 0);
        if (streamIndex < 0 || !codec) break;

        codecCtx = avcodec_alloc_context3(codec);
        if (!codecCtx) break;
        if (avcodec_parameters_to_context(codecCtx, format->streams[streamIndex]->codecpar) < 0) break;
        if (avcodec_open2(codecCtx, codec, nullptr) < 0) break;

        const int channels = codecCtx->ch_layout.nb_channels;
        const int sampleRate = codecCtx->sample_rate;
        if (channels <= 0 || sampleRate <= 0) break;

        AVChannelLayout outLayout;
        av_channel_layout_default(&outLayout, channels);
        if (swr_alloc_set_opts2(&swr, &outLayout, AV_SAMPLE_FMT_S16, sampleRate,
                                &codecCtx->ch_layout, codecCtx->sample_fmt, sampleRate,
                                0, nullptr) < 0) break;
        if (swr_init(swr) < 0) break;

        out = fopen(outputPath, "wb");
        if (!out) break;
        writeWavHeader(out, sampleRate, channels, 0);

        packet = av_packet_alloc();
        frame = av_frame_alloc();
        if (!packet || !frame) break;

        while (av_read_frame(format, packet) >= 0) {
            if (packet->stream_index == streamIndex && avcodec_send_packet(codecCtx, packet) >= 0) {
                while (avcodec_receive_frame(codecCtx, frame) >= 0) {
                    if (frame->nb_samples > outBufferSamples) {
                        av_freep(&outBuffer);
                        outBufferSamples = frame->nb_samples;
                        if (av_samples_alloc(&outBuffer, nullptr, channels, outBufferSamples,
                                             AV_SAMPLE_FMT_S16, 0) < 0) {
                            outBufferSamples = 0;
                            break;
                        }
                    }
                    const int converted = swr_convert(swr, &outBuffer, outBufferSamples,
                                                      const_cast<const uint8_t **>(frame->data),
                                                      frame->nb_samples);
                    if (converted > 0) {
                        fwrite(outBuffer, 1, static_cast<size_t>(converted) * channels * 2, out);
                        frames += converted;
                    }
                }
            }
            av_packet_unref(packet);
        }

        if (frames > 0) {
            fseek(out, 0, SEEK_SET);
            writeWavHeader(out, sampleRate, channels, frames);
            ok = true;
        }
    } while (false);

    if (outBuffer) av_freep(&outBuffer);
    if (out) fclose(out);
    if (frame) av_frame_free(&frame);
    if (packet) av_packet_free(&packet);
    if (swr) swr_free(&swr);
    if (codecCtx) avcodec_free_context(&codecCtx);
    avformat_close_input(&format);
    if (!ok) remove(outputPath);
    return ok;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_nami_core_tracker_FfmpegNative_nativeDecodeToWav(
        JNIEnv *env, jclass, jstring jInputPath, jstring jOutputPath) {
    const char *inputPath = env->GetStringUTFChars(jInputPath, nullptr);
    const char *outputPath = env->GetStringUTFChars(jOutputPath, nullptr);
    const bool ok = decodeToWav(inputPath, outputPath);
    env->ReleaseStringUTFChars(jInputPath, inputPath);
    env->ReleaseStringUTFChars(jOutputPath, outputPath);
    return ok ? JNI_TRUE : JNI_FALSE;
}
