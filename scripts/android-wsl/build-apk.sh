#!/usr/bin/env bash
# Builds the release APK in WSL (after setup.sh): pulls the Windows checkout's
# committed state into ~/Concat, builds, and copies the APK next to the
# Windows checkout as ../Concat-apk/Concat.apk. Uncommitted changes are not built.
set -euo pipefail

win_repo=$(cd "$(dirname "$0")/../.." && pwd)
. ~/.cargo/env
export ANDROID_HOME=~/Android/Sdk JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
# `.*` after the closing quote drops the CR the Windows checkout's CRLF lines carry.
ndk_version=$(sed -n 's/.*ndkVersion = "\([^"]*\)".*/\1/p' "$win_repo/android-native/app/build.gradle.kts")
# Ubuntu 20.04's cmake 3.16 and libclang 10 are too old for NDK 28: pip's cmake (setup.sh), the NDK's own libclang.
export PATH=~/.local/bin:$PATH LIBCLANG_PATH=$ANDROID_HOME/ndk/$ndk_version/toolchains/llvm/prebuilt/linux-x86_64/lib
export CARGO_APK_RELEASE_KEYSTORE=~/concat-local.keystore CARGO_APK_RELEASE_KEYSTORE_PASSWORD=concat

cd ~/Concat
git pull --ff-only
cd src
eval "$(scripts/mobile-env.sh aarch64-linux-android)"
# shortcut: rustc 1.93.1 ICEs rendering a missing_docs warning in concat's `platform` module; silence it. Drop after a toolchain bump.
export RUSTFLAGS="$RUSTFLAGS -A missing_docs"
cargo apk build --release -p concat-android --target aarch64-linux-android

out=$win_repo/../Concat-apk
mkdir -p "$out"
cp target/release/apk/Concat.apk "$out/"
echo "APK: $(wslpath -w "$out/Concat.apk")"
