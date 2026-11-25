#!/usr/bin/env bash
# Builds the Gradle release APK in WSL (after setup.sh): checks out in ~/Concat the
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

cd ~/Concat
git fetch origin refs/wsl-build
git checkout --detach FETCH_HEAD
cd src
# setup.sh installs it too; checked here so a WSL setup made before upstream switched to cargo-ndk still builds.
command -v cargo-ndk >/dev/null || cargo install cargo-ndk
# A copy out of the NDK, so it runs every build rather than once in setup.sh.
scripts/android-libs.sh aarch64-linux-android
cd ../android-native
# shortcut: rustc 1.93.1 ICEs rendering missing_docs and dead_code warnings; silence them. Drop after upgrading to a compiler without the ICE.
export CARGO_ENCODED_RUSTFLAGS=$'-C\x1flink-arg=-Wl,-z,max-page-size=16384\x1f-C\x1flink-arg=-Wl,--build-id=sha1\x1f-A\x1fmissing_docs\x1f-A\x1fdead_code'
./build-rust.sh release aarch64-linux-android
./gradlew --no-daemon assembleRelease -Pabis=arm64-v8a

apk=app/build/outputs/apk/release/app-release.apk
export ANDROID_KEYSTORE_PASSWORD=concat ANDROID_KEY_PASSWORD=concat
apksigner="$ANDROID_HOME/build-tools/35.0.0/apksigner"
"$apksigner" sign --ks ~/concat-local.keystore --ks-pass env:ANDROID_KEYSTORE_PASSWORD \
  --ks-key-alias concat --key-pass env:ANDROID_KEY_PASSWORD "$apk"
"$apksigner" verify --print-certs "$apk"

out=$win_repo/build
mkdir -p "$out"
cp "$apk" "$out/Concat.apk"
echo "APK: $(wslpath -w "$out/Concat.apk")"
