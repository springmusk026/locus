#!/usr/bin/env bash
#
# Cross-compiles llama.cpp (MIT) and Locus's JNI bridge for the GGUF on-device models.
#
# Outputs lib*.so files into core/ondevice/src/main/jniLibs/<abi>/:
#   liblocusllama.so          the JNI bridge (core/ondevice/src/main/cpp/locus_llama.cpp)
#   libllama.so               llama.cpp
#   libggml.so, libggml-base.so
#   libggml-cpu-*.so          ggml's CPU backend, one per instruction-set level (GGML_CPU_ALL_VARIANTS);
#                             at run time ggml loads the best one this CPU supports
#   libc++_shared.so          the NDK's C++ runtime, shared because the libraries above pass C++
#                             objects and exceptions between them
#
# Usage: tools/build-llama.sh [--clean]
#
# Needs: Android NDK r29 (ANDROID_NDK_HOME, or found in the SDK), CMake 3.22+ and Ninja (CMAKE and
# NINJA, or on PATH, or the SDK's cmake package), bash, curl, tar, sha256sum or shasum. Runs on Linux
# x86_64 (as on F-Droid's build server) or macOS.
#
# Inputs, all pinned below:
#   llama.cpp  LLAMA_TAG = LLAMA_COMMIT, from LLAMA_URL checked against LLAMA_SHA256 (MIT)
#   NDK        r29 (NDK_MAJOR); release builds use 29.0.14206865
# Optional environment:
#   LLAMA_SRC  an existing checkout of llama.cpp at LLAMA_COMMIT (e.g. an F-Droid srclib); no download
#   ABIS       ABIs to build (default "arm64-v8a x86_64")
#
# The output must be byte-identical wherever it's built, because F-Droid deletes the committed
# lib*.so files, runs this script and compares the APK with the published one (docs/FDROID.md).
# Hence: the source is copied without .git under the repo, paths are mapped to ".", the build info
# llama.cpp embeds is fixed here instead of read from git, there's no build ID, and .comment (which
# names the host's compiler build) is removed.

set -euo pipefail

LLAMA_TAG="b12066"
LLAMA_COMMIT="713319e28492d319b81e49646151f6bbdb9d4551"   # 2026-11-03
LLAMA_URL="https://github.com/ggml-org/llama.cpp/archive/refs/tags/${LLAMA_TAG}.tar.gz"
LLAMA_SHA256="f7ee761561563d761cd7233c36aa00f03935f9a534d2a8a4491e29b11bb9b0f2"
MIN_API=26
ABIS="${ABIS:-arm64-v8a x86_64}"
NDK_MAJOR=29

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="$ROOT/.build-native/llama"
OUTPUT_DIR="$ROOT/core/ondevice/src/main/jniLibs"
JNI_DIR="$ROOT/core/ondevice/src/main/cpp"

if [[ "${1:-}" == "--clean" ]]; then rm -rf "$BUILD_DIR"; echo "Cleaned."; exit 0; fi

for tool in curl tar; do
    command -v "$tool" > /dev/null || { echo "Missing $tool; see the header of $0 for what this script needs." >&2; exit 1; }
done

sha256() {
    if command -v sha256sum > /dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

find_sdk() {
    local sdk
    sdk=$(grep '^sdk.dir=' "$ROOT/local.properties" 2>/dev/null | cut -d= -f2- || true)
    for base in "$sdk" "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
        [[ -n "$base" && -d "$base" ]] && { echo "$base"; return; }
    done
}
SDK="$(find_sdk || true)"

find_ndk() {
    for v in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}"; do [[ -n "$v" && -d "$v" ]] && { echo "$v"; return; }; done
    if [[ -n "$SDK" && -d "$SDK/ndk" ]]; then
        local found
        found=$(ls -d "$SDK/ndk/${NDK_MAJOR}."* 2>/dev/null | sort -V | tail -1 || true)
        [[ -n "$found" ]] && { echo "$found"; return; }
    fi
    echo "Android NDK r${NDK_MAJOR} not found. Set ANDROID_NDK_HOME." >&2; exit 1
}

find_tool() { # name, env override
    local name=$1 override=$2
    [[ -n "$override" ]] && { echo "$override"; return; }
    command -v "$name" 2>/dev/null && return
    if [[ -n "$SDK" && -d "$SDK/cmake" ]]; then
        local found
        found=$(ls -d "$SDK/cmake/"*/bin/"$name" 2>/dev/null | sort -V | tail -1 || true)
        [[ -n "$found" ]] && { echo "$found"; return; }
    fi
    echo "$name not found. Install it or set $(echo "$name" | tr a-z A-Z)." >&2; exit 1
}

NDK="$(find_ndk)"
CMAKE_BIN="$(find_tool cmake "${CMAKE:-}")"
NINJA_BIN="$(find_tool ninja "${NINJA:-}")"
HOST_TAG=""
for tag in "$(uname -s | tr 'A-Z' 'a-z')-$(uname -m)" darwin-x86_64 linux-x86_64; do
    [[ -d "$NDK/toolchains/llvm/prebuilt/$tag" ]] && { HOST_TAG=$tag; break; }
done
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
JOBS="$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo 4)"
echo "NDK: $NDK ($HOST_TAG)"
echo "CMake: $CMAKE_BIN ($("$CMAKE_BIN" --version | head -1))"

# The source goes under $ROOT without .git, so the build sees the same files and paths whether it came
# from the tarball or a git checkout (llama.cpp's CMake reads git for its build info).
SRC="$BUILD_DIR/src"
mkdir -p "$BUILD_DIR"
if [[ -n "${LLAMA_SRC:-}" ]]; then
    if [[ -e "$LLAMA_SRC/.git" ]]; then
        actual=$(git -C "$LLAMA_SRC" rev-parse HEAD)
        [[ "$actual" == "$LLAMA_COMMIT" ]] || { echo "LLAMA_SRC is at $actual, expected $LLAMA_COMMIT ($LLAMA_TAG)." >&2; exit 1; }
    else
        [[ -f "$LLAMA_SRC/include/llama.h" ]] || { echo "LLAMA_SRC doesn't look like a llama.cpp source tree." >&2; exit 1; }
        echo "LLAMA_SRC has no .git; trusting it is $LLAMA_TAG."
    fi
    rm -rf "$SRC"; mkdir -p "$SRC"
    (cd "$LLAMA_SRC" && tar cf - --exclude=.git .) | (cd "$SRC" && tar xf -)
elif [[ ! -f "$SRC/.locus-$LLAMA_TAG" ]]; then
    curl -fsSL --retry 3 "$LLAMA_URL" -o "$BUILD_DIR/llama.tar.gz"
    actual=$(sha256 "$BUILD_DIR/llama.tar.gz")
    if [[ "$actual" != "$LLAMA_SHA256" ]]; then
        rm -f "$BUILD_DIR/llama.tar.gz"
        echo "llama.cpp $LLAMA_TAG tarball has SHA-256 $actual, expected $LLAMA_SHA256." >&2; exit 1
    fi
    rm -rf "$SRC"; mkdir -p "$SRC"
    tar xzf "$BUILD_DIR/llama.tar.gz" -C "$SRC" --strip-components=1
    rm "$BUILD_DIR/llama.tar.gz"
    touch "$SRC/.locus-$LLAMA_TAG"
fi

# Locus lists the architectures this llama.cpp can load (GgufArchitectures.kt), to say "unsupported"
# at import instead of failing at the first message. Keep the two in step on every bump.
check_architectures() {
    local listed known
    known=$(sed -n '/LLM_ARCH_NAMES = {/,/^};/p' "$SRC/src/llama-arch.cpp" | grep -o '"[^"]*"' | tr -d '"' | grep -v '^(unknown)$' | sort -u)
    listed=$(sed -n '/BEGIN llama.cpp architectures/,/END llama.cpp architectures/p' \
        "$ROOT/core/ondevice/src/main/kotlin/dev/locus/core/ondevice/gguf/GgufArchitectures.kt" | grep -o '"[^"]*"' | tr -d '"' | sort -u)
    if [[ "$known" != "$listed" ]]; then
        echo "GgufArchitectures.kt doesn't match llama.cpp $LLAMA_TAG's src/llama-arch.cpp:" >&2
        diff <(echo "$listed") <(echo "$known") >&2 || true
        echo "Update the list between the BEGIN/END markers." >&2
        exit 1
    fi
}
check_architectures

# ggml's CMake asks git for "the" commit, from its own source folder. The copy has no .git, so git
# would walk up to Locus's repository and embed Locus's commit (and a -dirty flag) in libggml-base.so,
# making every Locus commit a different library. Stop git's search at the build folder.
export GIT_CEILING_DIRECTORIES="$BUILD_DIR"

FLAGS="-ffile-prefix-map=$ROOT=. -ffile-prefix-map=$NDK=ndk"
LDFLAGS="-Wl,--build-id=none -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"

# arm64: every Android CPU level ggml knows (armv8.0 to armv9.2 with SME), picked at run time. x86_64
# runs on emulators and Chromebooks: one SSE4.2 build, since its 14 variants would add 20 MB for them.
cpu_flags() {
    case "$1" in
        arm64-v8a) echo "-DGGML_CPU_ALL_VARIANTS=ON" ;;
        x86_64) echo "-DGGML_CPU_ALL_VARIANTS=OFF -DGGML_SSE42=ON" ;;
    esac
}

build_abi() {
    local abi=$1 out="$OUTPUT_DIR/$1" dir="$BUILD_DIR/build-$1"
    rm -rf "$dir"; mkdir -p "$out"
    "$CMAKE_BIN" -S "$JNI_DIR" -B "$dir" -G Ninja \
        -DCMAKE_MAKE_PROGRAM="$NINJA_BIN" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" \
        -DANDROID_PLATFORM="android-$MIN_API" \
        -DANDROID_STL=c++_shared \
        -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_C_FLAGS="$FLAGS" -DCMAKE_CXX_FLAGS="$FLAGS" \
        -DCMAKE_SHARED_LINKER_FLAGS="$LDFLAGS" -DCMAKE_MODULE_LINKER_FLAGS="$LDFLAGS" \
        -DLLAMA_SRC="$SRC" \
        -DLLAMA_BUILD_NUMBER="${LLAMA_TAG#b}" -DLLAMA_BUILD_COMMIT="${LLAMA_COMMIT:0:7}" \
        -DBUILD_SHARED_LIBS=ON \
        -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_TOOLS=OFF -DLLAMA_BUILD_EXAMPLES=OFF \
        -DLLAMA_BUILD_SERVER=OFF -DLLAMA_BUILD_APP=OFF -DLLAMA_OPENSSL=OFF -DLLAMA_SUBPROCESS=OFF \
        -DGGML_NATIVE=OFF -DGGML_BACKEND_DL=ON $(cpu_flags "$abi") \
        -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF -DGGML_CPU_KLEIDIAI=OFF \
        > "$dir.log" 2>&1 || { tail -40 "$dir.log" >&2; echo "[llama/$abi] cmake configure failed (log: $dir.log)" >&2; exit 1; }
    "$CMAKE_BIN" --build "$dir" -j "$JOBS" >> "$dir.log" 2>&1 \
        || { tail -60 "$dir.log" >&2; echo "[llama/$abi] build failed (log: $dir.log)" >&2; exit 1; }

    rm -f "$out"/lib*.so
    find "$dir" -name 'lib*.so' -type f | while read -r lib; do cp "$lib" "$out/"; done
    local triple
    case "$abi" in arm64-v8a) triple=aarch64-linux-android ;; x86_64) triple=x86_64-linux-android ;; *) echo "unsupported ABI $abi" >&2; exit 1 ;; esac
    cp "$TOOLCHAIN/sysroot/usr/lib/$triple/libc++_shared.so" "$out/"
    for lib in "$out"/lib*.so; do
        "$TOOLCHAIN/bin/llvm-strip" --strip-unneeded "$lib"
        # Drop the compiler version string so builds are reproducible across hosts (F-Droid).
        "$TOOLCHAIN/bin/llvm-objcopy" --remove-section .comment "$lib" 2>/dev/null || true
    done
    echo "[llama/$abi] built -> $out ($(du -ch "$out"/lib*.so | tail -1 | cut -f1))"
}

check_output() {
    local bad=0
    for lib in "$OUTPUT_DIR"/*/lib*.so; do
        local aligns name
        name="$(basename "$(dirname "$lib")")/$(basename "$lib")"
        aligns=$("$TOOLCHAIN/bin/llvm-objdump" -p "$lib" | awk '/LOAD/ {print $NF}' | sort -u | tr '\n' ' ')
        for a in $aligns; do
            case "$a" in 2\*\*1[4-9]|2\*\*[2-9][0-9]) ;; *) echo "  $name: LOAD align $a, not 16 KB" >&2; bad=1 ;; esac
        done
        # libc++_shared.so is the NDK's own prebuilt (same bytes on every host), build ID included.
        if [[ "$(basename "$lib")" != libc++_shared.so ]] && "$TOOLCHAIN/bin/llvm-readelf" -n "$lib" 2>/dev/null | grep -q 'Build ID'; then echo "  $name has a build ID" >&2; bad=1; fi
        if grep -q -a "$ROOT" "$lib"; then echo "  $name contains the build path $ROOT" >&2; bad=1; fi
        printf '  %-40s %s\n' "$name" "$(wc -c < "$lib" | tr -d ' ') bytes"
    done
    [[ $bad -eq 0 ]] || { echo "Some libraries aren't reproducible or 16 KB aligned." >&2; exit 1; }
}

for abi in $ABIS; do build_abi "$abi"; done
echo "Checking 16 KB alignment, build IDs and paths:"
check_output
echo "Done."
