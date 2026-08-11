#!/usr/bin/env bash
#
# Glasses Assistant — local deploy to a USB-connected Android phone.
# Run this ON YOUR LAPTOP (where adb + the phone live), not in a cloud session.
#
#   chmod +x deploy-to-phone.sh
#   ./deploy-to-phone.sh
#
# Requires: adb (Android platform-tools), JDK 17+, and an Android SDK with
# platforms;android-34 + build-tools;34.0.0. If you only have adb and no build
# toolchain, ask Claude to bake a keyed APK for you instead and just run the
# single `adb install -r` line at the bottom.
set -euo pipefail

REPO_URL="https://github.com/kweh-innovative-technical-solutions/KITS-Faraday-EMP-Shielding.git"
BRANCH="claude/glasses-assistant-android-wujfb7"
WORKDIR="${1:-$HOME/glasses-assistant}"

say() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }

# 1) adb present and one authorized device attached ---------------------------
say "Checking adb + device"
command -v adb >/dev/null || { echo "adb not found. Install Android platform-tools first."; exit 1; }
if ! adb get-state >/dev/null 2>&1; then
  echo "No authorized device. Plug in the phone, enable USB debugging, and accept the RSA prompt on the phone."
  adb devices
  exit 1
fi
adb devices

# 2) Get the code -------------------------------------------------------------
say "Fetching the app (branch: $BRANCH)"
if [ ! -d "$WORKDIR/.git" ]; then
  git clone -b "$BRANCH" "$REPO_URL" "$WORKDIR"
else
  git -C "$WORKDIR" fetch origin "$BRANCH" && git -C "$WORKDIR" checkout "$BRANCH" && git -C "$WORKDIR" pull --ff-only origin "$BRANCH"
fi
cd "$WORKDIR/glasses-assistant-android"

# 3) local.properties: SDK path + API key (never committed) -------------------
say "Configuring local.properties (gitignored)"
touch local.properties

if ! grep -q '^sdk.dir=' local.properties; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  [ -n "$SDK" ] || { for c in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" "$HOME/AppData/Local/Android/Sdk"; do [ -d "$c" ] && SDK="$c" && break; done; }
  [ -n "${SDK:-}" ] || { echo "Could not find your Android SDK. Set ANDROID_HOME and re-run."; exit 1; }
  echo "sdk.dir=$SDK" >> local.properties
  echo "sdk.dir=$SDK"
fi

if ! grep -qE '^ANTHROPIC_KEY=.+' local.properties; then
  read -rsp "Paste your Anthropic API key (sk-ant-...), or press Enter to build keyless: " KEY; echo
  # strip any existing empty line, then append
  grep -v '^ANTHROPIC_KEY=' local.properties > local.properties.tmp || true
  mv local.properties.tmp local.properties
  echo "ANTHROPIC_KEY=$KEY" >> local.properties
fi

# 4) Build + install ----------------------------------------------------------
say "Building debug APK"
./gradlew assembleDebug

say "Installing to the phone"
adb install -r app/build/outputs/apk/debug/app-debug.apk

cat <<'DONE'

Installed. Now on the phone:
  1. Open "Glasses Assistant" -> tap "1. Grant permissions" (mic, Bluetooth, notifications).
  2. "2. Enable phone control" -> enable Glasses Assistant in Accessibility.
       Android 13+: if greyed out, App info -> menu (top-right) -> "Allow restricted settings", then enable.
  3. "3. Start assistant" -> tap the big TALK button -> say "open messages".

Expected: you hear "Listening" out the glasses, then a short confirmation, and Messages opens.
Top failures: (1) accessibility not enabled -> nothing happens; (2) the glasses' own
assistant grabbing the mic -> empty transcript ("I didn't catch that").
DONE
