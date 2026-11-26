// Checks core/ondevice/src/main/cpp/stop_strings.h, the stop-string hold-back of locus_llama.cpp's
// decode loop, without llama.cpp or a phone. Not part of the Gradle build (the bridge is built by
// tools/build-llama.sh); run it with any C++17 compiler:
//
//   c++ -std=c++17 -Wall -Wextra -I core/ondevice/src/main/cpp core/ondevice/src/test/cpp/stop_strings_test.cpp -o /tmp/stop_strings_test && /tmp/stop_strings_test

#include "stop_strings.h"

#include <cstdio>
#include <string>
#include <vector>

namespace {

int failures = 0;

void check(bool ok, const std::string & what) {
    if (!ok) {
        std::fprintf(stderr, "FAIL: %s\n", what.c_str());
        failures++;
    }
}

struct Result {
    std::string streamed;  // what the parser saw token by token (what the user watched appear)
    std::string reply;     // the final reply (streamed + what finish() gets)
    bool stopped = false;
};

// The bridge's loop: one piece per token, held back by locus::decide, the rest given at the end.
Result run(const std::vector<std::string> & tokens, const std::vector<std::string> & stops) {
    Result r;
    std::vector<std::string> pieces;
    std::string pending;
    for (const auto & t : tokens) {
        pieces.push_back(t);
        pending += t;
        const locus::HoldDecision d = locus::decide(pieces, pending, stops);
        if (d.stop_at != std::string::npos) {
            r.streamed += pending.substr(0, d.stop_at);
            pending.clear();
            r.stopped = true;
            break;
        }
        size_t bytes = 0;
        for (size_t i = 0; i < d.release; ++i) bytes += pieces[i].size();
        const std::string fed = pending.substr(0, bytes);
        check(locus::complete_utf8_prefix(fed) == fed.size(), "never feeds half a character: " + fed);
        r.streamed += fed;
        pending.erase(0, bytes);
        pieces.erase(pieces.begin(), pieces.begin() + static_cast<long>(d.release));
    }
    r.reply = r.streamed + pending;
    return r;
}

// Every way of cutting text into two-or-more tokens at byte offsets i < j.
void every_split(const std::string & text, const std::vector<std::string> & stops, const std::string & want, bool want_stop) {
    for (size_t i = 0; i <= text.size(); ++i) {
        for (size_t j = i; j <= text.size(); ++j) {
            const Result r = run({text.substr(0, i), text.substr(i, j - i), text.substr(j)}, stops);
            const std::string at = " (split at " + std::to_string(i) + "," + std::to_string(j) + ")";
            check(r.reply == want, "reply '" + r.reply + "' != '" + want + "'" + at);
            check(r.stopped == want_stop, "stopped" + at);
            if (want_stop) check(r.streamed == want, "streamed '" + r.streamed + "' != '" + want + "'" + at);
            check(want.compare(0, r.streamed.size(), r.streamed) == 0, "streamed text is a prefix of the reply" + at);
        }
    }
}

} // namespace

int main() {
    const std::vector<std::string> chatml = {"<|im_end|>", "<end_of_turn>"};

    // The report's case: a stop string over two tokens never shows its first half.
    Result r = run({"Hello", " world", "<|im_", "end|>", "ignored"}, chatml);
    check(r.stopped, "split stop string ends the turn");
    check(r.streamed == "Hello world", "no fragment streamed: '" + r.streamed + "'");
    check(r.reply == "Hello world", "no fragment in the reply: '" + r.reply + "'");

    r = run({"Done", "<end", "_of", "_turn", ">"}, chatml);
    check(r.streamed == "Done", "Gemma's stop over four tokens: '" + r.streamed + "'");

    // Text that only looks like a start is released once it can't be one.
    r = run({"a <", "|im", "portant", " b"}, chatml);
    check(!r.stopped, "not a stop string");
    check(r.streamed == "a <|important b", "released after all: '" + r.streamed + "'");

    // No stop strings: every complete token streams at once, as before.
    r = run({"x", "y"}, {});
    check(r.streamed == "xy", "no stops, no hold");

    // Multi-byte text before a stop string, cut at every pair of byte offsets.
    const std::string text = "Olá, 世界 😀 ";
    every_split(text + "<|im_end|>tail", chatml, text, true);
    every_split(text + "<|im_en", chatml, text + "<|im_en", false); // generation ended mid-prefix: kept
    every_split("<|im_end|>", chatml, "", true);

    // A long reply: only the held text is searched, so this stays linear.
    std::vector<std::string> many(200000, "word ");
    many.push_back("<|im_end|>");
    r = run(many, chatml);
    check(r.stopped && r.streamed.size() == 200000 * 5, "long reply");

    if (failures == 0) std::printf("stop_strings_test: all passed\n");
    return failures == 0 ? 0 : 1;
}
