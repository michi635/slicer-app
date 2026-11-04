#!/usr/bin/env bash
# Cross-compile OrcaSlicer's dependency superbuild (Boost, TBB, CGAL, OCCT, OpenCV, OpenVDB, ...) for Android.
# usage: build-deps.sh <orca-src> <build-dir> <destdir> <download-dir>
# Result: <destdir>/usr/local holds headers, static libraries and CMake package files.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
source "$HERE/orca.env"
SRC="$1"; BUILD="$2"; DESTDIR="$3"; DL="$4"
: "${ANDROID_NDK:?ANDROID_NDK must point to NDK $NDK_VERSION}"
: "${ANDROID_HOME:?ANDROID_HOME must point to the Android SDK}"
JOBS="${JOBS:-$(nproc)}"

# The dependency superbuild is driven with the SDK's CMake 3.22.1, as in the upstream Android port:
# several bundled dependencies still declare cmake_minimum_required < 3.5.
export PATH="$ANDROID_HOME/cmake/3.22.1/bin:$PATH"
cmake --version | head -1

cmake -S "$SRC/deps" -B "$BUILD" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$ANDROID_API" -DANDROID_STL=c++_shared \
  -DDESTDIR="$DESTDIR" -DDEP_DOWNLOAD_DIR="$DL"

# One dependency at a time; each one builds with all cores.
export CMAKE_BUILD_PARALLEL_LEVEL="$JOBS"
ninja -C "$BUILD" -j1 deps
echo "dependencies installed in $DESTDIR/usr/local"
