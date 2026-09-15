#define DISCORDPP_IMPLEMENTATION
#include "discordpp.h"
#include <jni.h>
#include <atomic>
#include <memory>
#include <string>

namespace {
// All SDK entry points run on Android's main thread. Callbacks may complete asynchronously.
std::unique_ptr<discordpp::Client> client;
std::atomic<int> result{0};
std::atomic<unsigned long> generation{0};

// Kotlin supplies standard UTF-8 bytes, preserving emoji without JNI's modified UTF-8.
std::string utf8(JNIEnv* env, jbyteArray text) {
    const auto length = env->GetArrayLength(text);
    std::string out(length, '\0');
    env->GetByteArrayRegion(text, 0, length, reinterpret_cast<jbyte*>(out.data()));
    return out;
}
}

extern "C" JNIEXPORT void JNICALL
Java_dev_nami_app_discord_DiscordSdk_nativeOpen(JNIEnv*, jobject, jlong id) {
    ++generation;
    client = std::make_unique<discordpp::Client>();
    client->SetApplicationId(static_cast<uint64_t>(id));
    result = 0;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_nami_app_discord_DiscordSdk_nativePublish(
    JNIEnv* env, jobject, jbyteArray title, jbyteArray description, jlong start, jlong end) {
    if (!client) return;
    discordpp::Activity activity;
    activity.SetType(discordpp::ActivityTypes::Listening);
    activity.SetDetails(utf8(env, title));
    activity.SetState(utf8(env, description));
    discordpp::ActivityTimestamps timestamps;
    timestamps.SetStart(start);
    if (end > 0) timestamps.SetEnd(end);
    activity.SetTimestamps(timestamps);
    // Local artwork and private server URLs must never be published as public assets.
    const auto request = ++generation;
    result = 0;
    client->UpdateRichPresence(activity, [request](discordpp::ClientResult response) {
        if (generation.load() == request) result = response.Successful() ? 1 : -1;
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_nami_app_discord_DiscordSdk_nativePoll(JNIEnv*, jobject) {
    discordpp::RunCallbacks();
    return result.load();
}

extern "C" JNIEXPORT void JNICALL
Java_dev_nami_app_discord_DiscordSdk_nativeClear(JNIEnv*, jobject) {
    ++generation;
    if (client) client->ClearRichPresence();
    result = 0;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_nami_app_discord_DiscordSdk_nativeClose(JNIEnv*, jobject) {
    ++generation;
    client.reset();
    result = 0;
}
