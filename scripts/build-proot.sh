#!/usr/bin/env bash
# Cross-compile the proot binary for Android ABIs with the NDK toolchain,
# and place the results at app/src/main/assets/bin/proot-<abi> so they get
# packaged into the APK.
#
# Usage:
#   ANDROID_NDK=/path/to/android-ndk scripts/build-proot.sh arm64-v8a armeabi-v7a
#
# If cross-compiling proot from source fails for the pinned version, use the
# Termux recipe as reference (termux-packages/packages/proot/build.sh) and copy
# the resulting binaries to app/src/main/assets/bin/proot-<abi> manually —
# the app only needs the file to exist at that path.
set -euo pipefail

NDK="${ANDROID_NDK:?Set ANDROID_NDK to your NDK directory (e.g. .../ndk/26.1.10909125)}"
PROOT_VERSION="5.3.1"
ABIS=("$@")
[ ${#ABIS[@]} -eq 0 ] && ABIS=(arm64-v8a armeabi-v7a)

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "Downloading proot v${PROOT_VERSION}..."
curl -sL "https://github.com/proot-me/proot/archive/refs/tags/v${PROOT_VERSION}.tar.gz" | tar xz -C "$WORK"
SRC="$WORK/proot-${PROOT_VERSION}"

OUT="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/bin"
mkdir -p "$OUT"

for ABI in "${ABIS[@]}"; do
    case "$ABI" in
        arm64-v8a)   CLANG=aarch64-linux-android26-clang ;;
        armeabi-v7a) CLANG=armv7a-linux-androideabi26-clang ;;
        x86_64)      CLANG=x86_64-linux-android26-clang ;;
        *) echo "Unsupported ABI: $ABI" >&2; exit 1 ;;
    esac
    CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/$CLANG"

    echo "Building proot for $ABI with $CLANG..."
    make -C "$SRC/src" -j"$(nproc)" \
        CC="$CC" \
        CFLAGS="-O2 -Wall -Wno-unused-parameter -static -I$SRC/src -I$SRC/src/talloc" \
        LDFLAGS="-static -s" \
        proot

    cp "$SRC/src/proot" "$OUT/proot-$ABI"
    chmod +x "$OUT/proot-$ABI"
    rm -f "$SRC/src/proot"  # force a clean rebuild for the next ABI
    echo "OK: $OUT/proot-$ABI"
done
