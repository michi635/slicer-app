#!/usr/bin/env bash
# Build libslic3r and our slicer executable (engine/core) for Android against the prebuilt dependencies.
# usage: build-engine.sh <work-dir> <deps-prefix> <out-dir>
#   <work-dir>/orcaslicer-src   patched OrcaSlicer checkout (fetch-orca.sh)
#   <work-dir>/mobile/core      is linked to engine/core; Orca's ORCA_MOBILE_CORE switch adds it to the build
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
source "$HERE/orca.env"
WORK="$1"; P="$2"; OUT="$3"
: "${ANDROID_NDK:?ANDROID_NDK must point to NDK $NDK_VERSION}"
JOBS="${JOBS:-$(nproc)}"
SRC="$WORK/orcaslicer-src"
BUILD="$WORK/build-engine"

mkdir -p "$WORK/mobile"
ln -sfn "$HERE/core" "$WORK/mobile/core"

LAUNCHER=()
if command -v ccache >/dev/null; then
  LAUNCHER=(-DCMAKE_C_COMPILER_LAUNCHER=ccache -DCMAKE_CXX_COMPILER_LAUNCHER=ccache)
fi

cmake -S "$SRC" -B "$BUILD" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$ANDROID_API" -DANDROID_STL=c++_shared \
  -DCMAKE_PREFIX_PATH="$P" -DCMAKE_FIND_ROOT_PATH="$P" -DORCA_DEPS_PREFIX="$P" \
  -DOpenCV_DIR="$P/sdk/native/jni/abi-$ABI" \
  -DSLIC3R_GUI=OFF -DSLIC3R_STATIC=ON -DSLIC3R_PCH=OFF -DSLIC3R_CAD=ON -DSLIC3R_ENC_CHECK=OFF \
  -DSLIC3R_WARNINGS=OFF -DBUILD_TESTS=OFF -DORCA_TOOLS=OFF -DORCA_BUILD_PYTHON_STUBGEN_MODULE=OFF \
  -DORCA_MOBILE_CORE=ON \
  "${LAUNCHER[@]}"

ninja -C "$BUILD" -j"$JOBS" slicercore

mkdir -p "$OUT"
STRIP="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
TRIPLE=aarch64-linux-android
[ "$ABI" = x86_64 ] && TRIPLE=x86_64-linux-android
# An executable named like a library: Android installs files from lib/<abi>/ into the app's native library
# directory, the one place an app may run its own binaries from.
"$STRIP" --strip-unneeded -o "$OUT/libslicercore.so" "$BUILD/mobile-core/slicercore"
cp "$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/$TRIPLE/libc++_shared.so" "$OUT/"
ls -la "$OUT"
# Every library the program needs must be either in $OUT or part of Android itself.
echo "needed libraries:"
"$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -d "$OUT/libslicercore.so" | grep NEEDED || true
