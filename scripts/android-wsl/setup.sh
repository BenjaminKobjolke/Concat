#!/usr/bin/env bash
# One-time setup for building the Android APK on Windows, inside WSL (Ubuntu).
# CI builds on Ubuntu (.github/workflows/mobile.yml); this mirrors it.
#
# First, as root, in the Ubuntu terminal:
#   sudo apt-get update && sudo apt-get install -y build-essential cmake clang libclang-dev openjdk-17-jdk-headless
# Then, from the Windows checkout:
#   wsl -d Ubuntu -- bash /mnt/d/<path>/Concat/scripts/android-wsl/setup.sh
# Then build with build-apk.bat.
#
# Installs, all under $HOME: Rust (pinned toolchain) + cargo-apk, the Android
# SDK/NDK, an LF clone of this checkout at ~/Concat (scripts in the Windows
# checkout have CRLF endings), a local signing key, and the vendored FFmpeg.
# Safe to re-run: finished steps are skipped.
set -euo pipefail

win_repo=$(cd "$(dirname "$0")/../.." && pwd)
# `.*` after the closing quote drops the CR the Windows checkout's CRLF lines carry.
toolchain=$(sed -n 's/^channel = "\([^"]*\)".*/\1/p' "$win_repo/src/rust-toolchain.toml")
ndk_version=$(sed -n 's/.*ndkVersion = "\([^"]*\)".*/\1/p' "$win_repo/android-native/app/build.gradle.kts")

# Rust, the pinned toolchain, plus the phone target.
if [ ! -x ~/.cargo/bin/rustup ]; then
  curl -fsSL https://sh.rustup.rs | sh -s -- -y --default-toolchain none
fi
. ~/.cargo/env
rustup toolchain install "$toolchain" --profile minimal --target aarch64-linux-android
command -v cargo-apk >/dev/null || cargo "+$toolchain" install cargo-apk

# Linux Android SDK + NDK (the Windows SDK's NDK cannot run under Linux).
sdk=~/Android/Sdk
if [ ! -x "$sdk/cmdline-tools/latest/bin/sdkmanager" ]; then
  mkdir -p "$sdk/cmdline-tools"
  curl -fsSL -o /tmp/cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
  unzip -q -o /tmp/cmdtools.zip -d "$sdk/cmdline-tools"
  mv "$sdk/cmdline-tools/cmdline-tools" "$sdk/cmdline-tools/latest"
fi
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
yes | "$sdk/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null || true  # yes gets SIGPIPE under pipefail
"$sdk/cmdline-tools/latest/bin/sdkmanager" "platforms;android-35" "build-tools;35.0.0" "platform-tools" "ndk;$ndk_version"

# Ubuntu 20.04's cmake 3.16 is too old for NDK 28 (whisper.cpp); build-apk.sh puts ~/.local/bin first.
python3 -m pip install --user -q "cmake<4"

# LF clone on the Linux filesystem; build-apk.sh checks out the Windows checkout's snapshot in it.
[ -d ~/Concat ] || git clone "$win_repo" ~/Concat

# Signing key for release builds (local only, like CI's per-run key).
[ -f ~/concat-local.keystore ] || keytool -genkeypair -keystore ~/concat-local.keystore \
  -storepass concat -keypass concat -alias concat -keyalg RSA -keysize 2048 \
  -validity 10000 -dname "CN=Concat, O=Concat" >/dev/null

# shortcut: Ubuntu 20.04's curl 7.68 lacks --retry-all-errors (7.71+), which the vendor scripts pass; drop it. Remove on a newer Ubuntu.
mkdir -p ~/bin
cat > ~/bin/curl <<'WRAP'
#!/usr/bin/env bash
args=(); for a in "$@"; do [ "$a" = --retry-all-errors ] || args+=("$a"); done
exec /usr/bin/curl "${args[@]}"
WRAP
chmod +x ~/bin/curl
export PATH=~/bin:$PATH

export ANDROID_HOME=$sdk
cd ~/Concat/src
# FFmpeg source from GitHub's mirror: ffmpeg.org kept resetting TLS connections.
# ffmpeg-mobile.sh skips its own download when the source is already unpacked.
ffmpeg_version=$(sed -n 's/^version=\${FFMPEG_VERSION:-\(.*\)}/\1/p' scripts/ffmpeg-mobile.sh)
ffsrc=vendor/ffmpeg/src
if [ ! -f "$ffsrc/ffmpeg-$ffmpeg_version/configure" ]; then
  mkdir -p "$ffsrc"
  curl -fsSL --retry 5 -o "$ffsrc/n$ffmpeg_version.tar.gz" \
    "https://github.com/FFmpeg/FFmpeg/archive/refs/tags/n$ffmpeg_version.tar.gz"
  tar -xzf "$ffsrc/n$ffmpeg_version.tar.gz" -C "$ffsrc"
  mv "$ffsrc/FFmpeg-n$ffmpeg_version" "$ffsrc/ffmpeg-$ffmpeg_version"
fi
# FFmpeg builds from source: slow, once.
[ -d vendor/ffmpeg/aarch64-linux-android/lib ] || scripts/ffmpeg-mobile.sh aarch64-linux-android
echo "Setup done. Build with scripts/android-wsl/build-apk.bat from Windows."
