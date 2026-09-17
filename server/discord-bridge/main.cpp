// One authenticated Discord user per process. No local RPC or unauthenticated fallback.
#define DISCORDPP_IMPLEMENTATION
#include "discordpp.h"
#include <chrono>
#include <deque>
#include <iostream>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>

using Clock = std::chrono::steady_clock;
using namespace std::chrono_literals;

struct Input {
    std::mutex mutex;
    std::deque<std::string> lines;
    bool ended = false;
};

std::string unhex(const std::string& input) {
    if (input.size() % 2 || input.size() > 16384) throw std::runtime_error("invalid input");
    std::string result;
    auto digit = [](char c) -> unsigned {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        throw std::runtime_error("invalid input");
    };
    for (size_t i = 0; i < input.size(); i += 2)
        result.push_back(static_cast<char>(digit(input[i]) * 16 + digit(input[i + 1])));
    return result;
}

int main(int argc, char** argv) {
    if (argc == 2 && std::string(argv[1]) == "--check") {
        // Construct the actual SDK client so missing/incompatible native libraries fail the probe.
        discordpp::Client probe;
        std::cout << "nami-discord-bridge-v1\n";
        return 0;
    }
    if (argc != 1) return 2;
    auto input = std::make_shared<Input>();
    std::thread([input] {
        std::string line;
        while (std::getline(std::cin, line)) {
            std::lock_guard lock(input->mutex);
            if (line.size() > 32768 || input->lines.size() >= 64) break;
            input->lines.push_back(line);
            if (line == "QUIT") break;
        }
        std::lock_guard lock(input->mutex);
        input->ended = true;
    }).detach();

    auto client = std::make_shared<discordpp::Client>();
    uint64_t expected_user = 0, version = 0, sent = 0, generation = 0;
    std::string title, description, artwork_url;
    int64_t start = 0, end = 0;
    bool token_ready = false, fatal = false, was_ready = false, quit = false;
    auto last_input = Clock::now();
    auto last_publish = Clock::now() - 5s;
    auto last_ready = Clock::now();
    auto owned = [&] {
        if (!token_ready || client->GetStatus() != discordpp::Client::Status::Ready) return false;
        auto user = client->GetCurrentUserV2();
        return user && user->Id() == expected_user;
    };
    try {
        while (!quit && !fatal) {
            std::deque<std::string> lines;
            bool ended;
            { std::lock_guard lock(input->mutex); lines.swap(input->lines); ended = input->ended; }
            for (auto& line : lines) {
                std::istringstream stream(line);
                std::string op;
                stream >> op;
                last_input = Clock::now();
                if (op == "QUIT") { quit = true; break; }
                if (op == "PING") continue;
                if (op == "TOKEN") {
                    uint64_t user;
                    std::string token;
                    if (!(stream >> user >> token) || !user || (expected_user && expected_user != user))
                        throw std::runtime_error("invalid account");
                    expected_user = user;
                    token_ready = false;
                    const auto request = ++generation;
                    client->UpdateToken(discordpp::AuthorizationTokenType::Bearer, unhex(token),
                        [&, request](discordpp::ClientResult result) {
                            if (generation != request || quit) return;
                            if (!result.Successful()) {
                                std::cerr << "UpdateToken failed: " << result.Error() << std::endl;
                                fatal = true;
                                return;
                            }
                            token_ready = true;
                            if (client->GetStatus() == discordpp::Client::Status::Disconnected) client->Connect();
                        });
                    sent = 0;
                } else if (op == "SET") {
                    std::string title_hex, description_hex, artwork_hex;
                    // artwork_hex is last on the line and legitimately empty when there's no
                    // artwork (hex::encode("") on the server side is "") - operator>> refuses to
                    // extract an empty whitespace-delimited token at all, which made every SET
                    // with no artwork throw here. getline for the tail of the line instead - it
                    // returns "" for a bare trailing space/newline instead of failing the stream.
                    if (!(stream >> version >> title_hex >> description_hex >> start >> end) || !version)
                        throw std::runtime_error("invalid activity");
                    stream >> std::ws;
                    std::getline(stream, artwork_hex);
                    title = unhex(title_hex); description = unhex(description_hex); artwork_url = unhex(artwork_hex);
                    if (title.empty() || title.size() > 512 || description.size() > 512 || start <= 0 || (end && end < start)
                        || artwork_url.size() > 512)
                        throw std::runtime_error("invalid activity");
                } else throw std::runtime_error("invalid command");
            }
            if (quit || ended || Clock::now() - last_input > 45s) break;
            discordpp::RunCallbacks();
            if (token_ready && client->GetStatus() == discordpp::Client::Status::Ready && !owned()) {
                fatal = true; break;
            }
            const bool ready = owned();
            if (ready) last_ready = Clock::now();
            if (!ready && was_ready) {
                std::cout << "connecting " << version << std::endl;
                sent = 0;
            }
            was_ready = ready;
            if (Clock::now() - last_ready > 45s) { fatal = true; break; }
            if (ready && version && sent != version && Clock::now() - last_publish >= 5s) {
                discordpp::Activity activity;
                activity.SetType(discordpp::ActivityTypes::Listening);
                activity.SetDetails(title);
                activity.SetState(description);
                discordpp::ActivityTimestamps timestamps;
                timestamps.SetStart(start);
                if (end) timestamps.SetEnd(end);
                activity.SetTimestamps(timestamps);
                if (!artwork_url.empty()) {
                    discordpp::ActivityAssets assets;
                    assets.SetLargeImage(artwork_url);
                    assets.SetLargeText(title);
                    // "nami_logo" - an Art Asset the server admin uploads once in the Discord
                    // Developer Portal (Rich Presence -> Art Assets) under this exact key. See
                    // docs/discord-server-oauth.md. Only shown alongside a real large image -
                    // a small badge with no large image to sit on top of looks like a mistake.
                    assets.SetSmallImage(std::string("nami_logo"));
                    assets.SetSmallText(std::string("Nami"));
                    activity.SetAssets(assets);
                }
                const auto request = version;
                client->UpdateRichPresence(activity, [&, request](discordpp::ClientResult result) {
                    if (quit || request != version) return;
                    std::cout << (result.Successful() ? "published " : "error ") << request << std::endl;
                    if (!result.Successful()) {
                        std::cerr << "UpdateRichPresence failed: " << result.Error() << std::endl;
                        fatal = true;
                    }
                });
                sent = version;
                last_publish = Clock::now();
            }
            std::this_thread::sleep_for(20ms);
        }
    } catch (const std::exception& e) { std::cerr << "exception: " << e.what() << std::endl; fatal = true; }
    catch (...) { std::cerr << "unknown exception" << std::endl; fatal = true; }
    quit = true;
    ++generation;
    // Only touch presence of the authenticated expected user, even during shutdown.
    if (owned()) {
        client->ClearRichPresence();
        const auto until = Clock::now() + 500ms;
        while (Clock::now() < until) { discordpp::RunCallbacks(); std::this_thread::sleep_for(20ms); }
    }
    client->Disconnect();
    const auto until = Clock::now() + 1s;
    while (Clock::now() < until && client->GetStatus() != discordpp::Client::Status::Disconnected) {
        discordpp::RunCallbacks(); std::this_thread::sleep_for(20ms);
    }
    if (fatal) std::cout << "error " << version << std::endl;
    return fatal ? 1 : 0;
}
