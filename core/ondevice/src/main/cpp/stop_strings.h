// Template stop strings for locus_llama.cpp's decode loop, kept apart from llama.cpp and JNI so
// core/ondevice/src/test/cpp/stop_strings_test.cpp can check them with any C++17 compiler.
//
// A stop string (`<|im_end|>`, `<end_of_turn>`) can arrive over several tokens. Text that might be
// the start of one is held back until the next tokens show whether it is, so no fragment of a stop
// string is ever streamed. Only the held text is searched, so each token costs the same however
// long the reply already is.

#pragma once

#include <algorithm>
#include <cstddef>
#include <string>
#include <vector>

namespace locus {

// Length of the longest prefix of s that ends on a complete UTF-8 sequence. A token can end in the
// middle of a multi-byte character; the rest comes with the next token.
inline size_t complete_utf8_prefix(const std::string & s) {
    const size_t n = s.size();
    // Look back at most 3 bytes for the lead byte of an unfinished sequence.
    for (size_t back = 1; back <= 4 && back <= n; ++back) {
        const unsigned char c = static_cast<unsigned char>(s[n - back]);
        if ((c & 0xC0) == 0x80) continue; // continuation byte
        size_t need = 1;
        if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        return back >= need ? n : n - back;
    }
    return n;
}

// Where the earliest stop string starts in text, or npos.
inline size_t find_stop(const std::string & text, const std::vector<std::string> & stops) {
    size_t best = std::string::npos;
    for (const auto & st : stops) {
        if (st.empty()) continue;
        best = std::min(best, text.find(st));
    }
    return best;
}

// How many bytes at the end of text could be the beginning of a stop string still to come.
inline size_t stop_prefix_tail(const std::string & text, const std::vector<std::string> & stops) {
    size_t hold = 0;
    for (const auto & st : stops) {
        if (st.size() < 2) continue;
        for (size_t len = std::min(st.size() - 1, text.size()); len > hold; --len) {
            if (text.compare(text.size() - len, len, st, 0, len) == 0) {
                hold = len;
                break;
            }
        }
    }
    return hold;
}

// What to do with the held pieces (one per token, `text` is their concatenation) after a token.
struct HoldDecision {
    size_t release = 0;                   // how many leading pieces can go to the parser now
    size_t stop_at = std::string::npos;   // where a stop string starts in text (the turn ends there)
};

// Releases whole pieces only, up to the first byte that could belong to a stop string and never
// inside a UTF-8 character.
inline HoldDecision decide(const std::vector<std::string> & pieces, const std::string & text, const std::vector<std::string> & stops) {
    HoldDecision d;
    d.stop_at = find_stop(text, stops);
    if (d.stop_at != std::string::npos) return d;
    const size_t limit = std::min(text.size() - stop_prefix_tail(text, stops), complete_utf8_prefix(text));
    size_t end = 0;
    for (size_t i = 0; i < pieces.size(); ++i) {
        end += pieces[i].size();
        if (end > limit) break;
        // A boundary inside the text is a character boundary when the next byte isn't a continuation.
        const bool whole_char = end == text.size() || (static_cast<unsigned char>(text[end]) & 0xC0) != 0x80;
        if (whole_char) d.release = i + 1;
    }
    return d;
}

} // namespace locus
