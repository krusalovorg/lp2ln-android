#!/usr/bin/env bash
# Reproduce the arm64 direct-Flow voice preview with the checked-in preview lock.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
ndk="${ANDROID_NDK_HOME:-${ANDROID_HOME:?}/ndk/27.3.13750724}"
export ANDROID_NDK_HOME="$ndk"
prefix="${OPUS_LIB_DIR:-$root/build/voice-opus-arm64}"
if [[ ! -f "$prefix/lib/libopus.a" ]]; then
  "$root/scripts/build-voice-opus.sh" android "$prefix"
fi
export OPUS_LIB_DIR="$prefix" OPUS_NO_PKG=1 OPUS_STATIC=1
bin="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
export CC_aarch64_linux_android="$bin/aarch64-linux-android29-clang"
export CXX_aarch64_linux_android="$bin/aarch64-linux-android29-clang++"
export AR_aarch64_linux_android="$bin/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC_aarch64_linux_android"
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$root/app/build/voice-target}"
export CARGO_PROFILE_DEV_DEBUG=0 CARGO_INCREMENTAL=0
(cd "$root/rust-preview" && cargo +1.100.0 build --locked --target aarch64-linux-android)
dest="$root/app/build/generated/lp2ln/jniLibs/arm64-v8a"
mkdir -p "$dest"
"$bin/llvm-strip" -o "$dest/liblp2ln_android.so" "$CARGO_TARGET_DIR/aarch64-linux-android/debug/liblp2ln_android.so"
cd "$root"
"${GRADLE:-./gradlew}" :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest \
  -PrustAbis=arm64-v8a -PvoicePreview=true -PskipRustBuild \
  -PapplicationIdOverride=lp2ln_android.krusalov.org.realtimepreview "$@"
