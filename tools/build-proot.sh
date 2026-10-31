#!/usr/bin/env bash
#
# Cross-compiles proot (and talloc) from pinned sources for Locus's Linux workspace.
#
# Adapted from Kai's build-proot.sh (https://github.com/SimonSchubert/Kai, Apache-2.0), with
# 16 KB page alignment added for Android 15+ devices.
#
# Outputs lib*.so files into core/linux/src/main/jniLibs/<abi>/. They are executables named like
# libraries so Android extracts them to the native library directory, where an app may run files.
#
# proot is GPL-2.0-or-later and runs as a separate program; see core/linux/NOTICE for the source
# offer. talloc is LGPL-3.0-or-later.
#
# Usage: tools/build-proot.sh [--clean]
# Needs: Android NDK r29 (ANDROID_NDK_HOME, or found in the SDK), python3, git, curl, make.

set -euo pipefail

PROOT_REPO="https://github.com/termux/proot.git"
PROOT_COMMIT="4dba3afbf3a63af89b4d9c1a59bf2bda10f4d10f"   # 2026-01-18, same as Kai's known-good build
TALLOC_VERSION="2.4.3"
TALLOC_URL="https://www.samba.org/ftp/talloc/talloc-${TALLOC_VERSION}.tar.gz"
MIN_API=26
ABIS="${ABIS:-arm64-v8a x86_64}"
NDK_MAJOR=29
PAGE_FLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="$ROOT/.build-native"
OUTPUT_DIR="$ROOT/core/linux/src/main/jniLibs"

if [[ "${1:-}" == "--clean" ]]; then rm -rf "$BUILD_DIR"; echo "Cleaned."; exit 0; fi

triple() {
    case "$1" in
        arm64-v8a) echo aarch64-linux-android ;;
        x86_64) echo x86_64-linux-android ;;
        *) echo "unsupported ABI $1" >&2; exit 1 ;;
    esac
}

find_ndk() {
    for v in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}"; do [[ -n "$v" && -d "$v" ]] && { echo "$v"; return; }; done
    local sdk
    sdk=$(grep '^sdk.dir=' "$ROOT/local.properties" 2>/dev/null | cut -d= -f2- || true)
    for base in "$sdk" "${ANDROID_HOME:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
        [[ -n "$base" && -d "$base/ndk" ]] || continue
        local found
        found=$(ls -d "$base/ndk/${NDK_MAJOR}."* 2>/dev/null | sort -V | tail -1 || true)
        [[ -n "$found" ]] && { echo "$found"; return; }
    done
    echo "Android NDK r${NDK_MAJOR} not found. Set ANDROID_NDK_HOME." >&2; exit 1
}

NDK="$(find_ndk)"
HOST_TAG=""
for tag in "$(uname -s | tr 'A-Z' 'a-z')-$(uname -m)" darwin-x86_64 linux-x86_64; do
    [[ -d "$NDK/toolchains/llvm/prebuilt/$tag" ]] && { HOST_TAG=$tag; break; }
done
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
JOBS="$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo 4)"
echo "NDK: $NDK ($HOST_TAG)"

mkdir -p "$BUILD_DIR"
[[ -d "$BUILD_DIR/proot" ]] || git clone --quiet "$PROOT_REPO" "$BUILD_DIR/proot"
git -C "$BUILD_DIR/proot" checkout -q "$PROOT_COMMIT"
if [[ ! -d "$BUILD_DIR/talloc-$TALLOC_VERSION" ]]; then
    curl -sL "$TALLOC_URL" -o "$BUILD_DIR/talloc.tar.gz"
    tar xzf "$BUILD_DIR/talloc.tar.gz" -C "$BUILD_DIR"
    rm "$BUILD_DIR/talloc.tar.gz"
fi

build_talloc() {
    local abi=$1 cc="$TOOLCHAIN/$(triple "$1")${MIN_API}-clang" prefix="$BUILD_DIR/sysroot/$1"
    [[ -f "$prefix/lib/libtalloc.a" ]] && { echo "[talloc/$abi] cached"; return; }
    local dir="$BUILD_DIR/build-talloc-$abi"
    rm -rf "$dir"; cp -r "$BUILD_DIR/talloc-$TALLOC_VERSION" "$dir"; mkdir -p "$prefix"
    # WAF can't run test programs when cross-compiling; answer its checks up front.
    cat > "$dir/cross-answers.txt" <<'ANSWERS'
Checking uname sysname type: "Linux"
Checking uname machine type: "dontcare"
Checking uname release type: "dontcare"
Checking uname version type: "dontcare"
Checking simple C program: OK
building library support: OK
Checking for large file support: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for WORDS_BIGENDIAN: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SECURE_MKSTEMP: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking correct behavior of strtoll: OK
Checking correct behavior of strptime: OK
Checking for HAVE_IFACE_GETIFADDRS: OK
Checking for HAVE_IFACE_IFCONF: OK
Checking for HAVE_IFACE_IFREQ: OK
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for working strptime: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: OK
Checking getconf large file support flags work: OK
ANSWERS
    (
        cd "$dir"
        CC="$cc" CFLAGS="-ffile-prefix-map=$ROOT=." LDFLAGS="$PAGE_FLAGS" \
        AR="$TOOLCHAIN/llvm-ar" RANLIB="$TOOLCHAIN/llvm-ranlib" STRIP="$TOOLCHAIN/llvm-strip" \
        ./configure --prefix="$prefix" --disable-rpath --disable-python --cross-compile --cross-answers=cross-answers.txt > /dev/null
        make -j"$JOBS" > /dev/null
        make install > /dev/null
    )
    # Link proot against a static talloc: the shared library's soname is libtalloc.so.2, and an APK
    # can only ship files named lib*.so. The library object only needs libc.
    local obj
    obj=$(python3 - "$dir/bin/default/compile_commands.json" <<'PY'
import json, sys
for c in json.load(open(sys.argv[1])):
    if c["file"].endswith("/talloc.c") or c["file"] == "../../talloc.c":
        if "-fPIC" in c["arguments"]:
            print([a[2:] if a.startswith("-o") else a for a in c["arguments"] if a.endswith(".o")][-1])
            break
PY
)
    "$TOOLCHAIN/llvm-ar" rcs "$prefix/lib/libtalloc.a" "$obj"
    rm -f "$prefix/lib/libtalloc.so" "$prefix/lib/libtalloc.so.2" "$prefix/lib/libtalloc.so.2."*
    echo "[talloc/$abi] built (static)"
}

build_proot() {
    local abi=$1 cc="$TOOLCHAIN/$(triple "$1")${MIN_API}-clang" sysroot="$BUILD_DIR/sysroot/$1"
    local dir="$BUILD_DIR/build-proot-$abi" out="$OUTPUT_DIR/$abi"
    rm -rf "$dir"; cp -r "$BUILD_DIR/proot" "$dir"; mkdir -p "$out" "$BUILD_DIR/bin"
    # No 32-bit loader: Locus runs a 64-bit distro only.
    sed -i.bak '/#define HAS_LOADER_32BIT/d' "$dir/src/arch.h"
    ln -sf "$TOOLCHAIN/llvm-readelf" "$BUILD_DIR/bin/readelf"
    (
        cd "$dir"
        export CC="$cc" LD="$cc" STRIP="$TOOLCHAIN/llvm-strip" OBJCOPY="$TOOLCHAIN/llvm-objcopy" OBJDUMP="$TOOLCHAIN/llvm-objdump"
        export CFLAGS="-DARG_MAX=131072 -ffile-prefix-map=$ROOT=. -I$sysroot/include -Wno-error=implicit-function-declaration -Wno-error=int-conversion"
        export LDFLAGS="-L$sysroot/lib $PAGE_FLAGS"
        export LOADER_LDFLAGS="$PAGE_FLAGS"
        export PATH="$BUILD_DIR/bin:$PATH"
        make -C src PROOT_UNBUNDLE_LOADER="loader-out" -j"$JOBS" > /dev/null
    )
    cp "$dir/src/proot" "$out/libproot.so"
    cp "$dir/src/loader/loader" "$out/libproot-loader.so"
    rm -f "$out/libtalloc.so"   # talloc is linked into proot statically
    for lib in "$out"/lib*.so; do
        "$TOOLCHAIN/llvm-strip" "$lib"
        # Drop the compiler version string so builds are reproducible across hosts (F-Droid).
        "$TOOLCHAIN/llvm-objcopy" --remove-section .comment "$lib" 2>/dev/null || true
    done
    echo "[proot/$abi] built -> $out"
}

check_alignment() {
    local bad=0
    for lib in "$OUTPUT_DIR"/*/lib*.so; do
        local aligns
        aligns=$("$TOOLCHAIN/llvm-objdump" -p "$lib" | awk '/LOAD/ {print $NF}' | sort -u | tr '\n' ' ')
        echo "  $(basename "$(dirname "$lib")")/$(basename "$lib"): LOAD align $aligns"
        for a in $aligns; do
            case "$a" in 2\*\*1[4-9]|2\*\*[2-9][0-9]) ;; *) bad=1 ;; esac
        done
    done
    [[ $bad -eq 0 ]] || { echo "Some segments are not 16 KB aligned." >&2; exit 1; }
}

for abi in $ABIS; do build_talloc "$abi"; build_proot "$abi"; done
echo "Checking 16 KB page alignment:"
check_alignment
echo "Done."
