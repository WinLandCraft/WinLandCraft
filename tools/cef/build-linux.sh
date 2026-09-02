#!/usr/bin/env bash
set -euo pipefail

readonly work_root=${WLC_CEF_WORK_ROOT:-/work}
readonly cef_commit=89cd5813e47d84c68e56ced336c2c01b7dc77b8d
readonly chromium_version=151.0.7922.34
readonly checkout="$work_root/linux"
readonly chromium="$checkout/chromium/src"

test "$(git -C "$checkout/cef" rev-parse HEAD)" = "$cef_commit"
test "$(awk -F= '/^MAJOR=/{a=$2}/^MINOR=/{b=$2}/^BUILD=/{c=$2}/^PATCH=/{d=$2}END{print a"."b"."c"."d}' "$chromium/chrome/VERSION")" = "$chromium_version"

"$chromium/build/install-build-deps.sh" --no-prompt --no-arm --no-chromeos-fonts

export PATH="$work_root/depot_tools:$PATH"
export CEF_ARCHIVE_FORMAT=tar.bz2
export CEF_USE_GN=1
export NINJA_CORE_MULTIPLIER=${NINJA_CORE_MULTIPLIER:-0.45}
export GN_DEFINES='is_official_build=true proprietary_codecs=true ffmpeg_branding=Chrome use_sysroot=true use_vaapi=true chrome_pgo_phase=0 use_thin_lto=false is_cfi=false symbol_level=0 blink_symbol_level=0 v8_symbol_level=0 cef_api_version=15100'

python3 "$work_root/tools/automate-git.py" \
  --download-dir="$checkout" \
  --depot-tools-dir="$work_root/depot_tools" \
  --branch=7922 \
  --checkout="$cef_commit" \
  --no-update \
  --force-build \
  --force-distrib \
  --x64-build \
  --no-debug-build \
  --build-target=cefsimple \
  --no-distrib-docs \
  --no-distrib-symbols
