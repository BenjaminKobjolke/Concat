#!/usr/bin/env bash
# Builds the release APK in WSL (after setup.sh): checks out in ~/Concat the
# working-tree snapshot build-apk.bat stored as refs/wsl-build, builds, and copies
# the APK into the Windows checkout as build/Concat.apk (git-ignored). Start it
# through build-apk.bat: run alone it builds the last snapshot. Files not yet
# `git add`ed are not in the snapshot.
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
git fetch origin refs/wsl-build
git checkout --detach FETCH_HEAD
cd src
# A copy out of the NDK, so it runs every build rather than once in setup.sh.
scripts/android-libs.sh aarch64-linux-android
# Two lines: a failing command substitution inside eval's argument does not trip set -e.
env=$(scripts/mobile-env.sh aarch64-linux-android)
eval "$env"
# shortcut: rustc 1.93.1 ICEs rendering missing_docs and dead_code warnings; silence them. Drop after upgrading to a compiler without the ICE.
export RUSTFLAGS="$RUSTFLAGS -A missing_docs -A dead_code"
cargo apk build --release -p concat-android --target aarch64-linux-android

out=$win_repo/build
mkdir -p "$out"
cp target/release/apk/Concat.apk "$out/"
echo "APK: $(wslpath -w "$out/Concat.apk")"
