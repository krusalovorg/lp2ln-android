#!/usr/bin/env bash
# Usage: build-voice-opus.sh host|android OUTPUT_PREFIX
set -euo pipefail
platform=${1:?host or android}
prefix=$(realpath -m "${2:?output prefix}")
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
curl --fail --location --retry 3 https://downloads.xiph.org/releases/opus/opus-1.6.1.tar.gz -o "$work/opus.tgz"
echo "6ffcb593207be92584df15b32466ed64bbec99109f007c82205f0194572411a1  $work/opus.tgz" | sha256sum -c -
tar xf "$work/opus.tgz" -C "$work"
args=(-DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DCMAKE_BUILD_TYPE=Release)
case "$platform" in
  android) args+=("-DCMAKE_TOOLCHAIN_FILE=${ANDROID_NDK_HOME:?}/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29);;
  host) ;;
  *) exit 2;;
esac
cmake -S "$work/opus-1.6.1" -B "$work/build" "${args[@]}" -DCMAKE_INSTALL_PREFIX="$prefix" -DCMAKE_INSTALL_LIBDIR=lib
cmake --build "$work/build" -j 4
cmake --install "$work/build"
