#!/usr/bin/env bash
set -euo pipefail

if (($# != 5)); then
  echo 'usage: assemble-runtime.sh <platform> <stock-jcef.tar.gz> <cef-distribution> <args.gn> <output-dir>' >&2
  exit 2
fi

readonly platform=$1
readonly stock_archive=$2
readonly cef_distribution=$3
readonly args_file=$4
readonly output_dir=$5
readonly cef_commit=89cd5813e47d84c68e56ced336c2c01b7dc77b8d
readonly jcef_commit=2eb4ca2648bda91d1dfed81e9a37ba92e757aff9
readonly chromium_version=151.0.7922.34

case "$platform" in
  linux_*) libcef=libcef.so ;;
  windows_*) libcef=libcef.dll ;;
  macos_*) libcef='Chromium Embedded Framework.framework/Chromium Embedded Framework' ;;
  *) echo "unsupported platform: $platform" >&2; exit 2 ;;
esac

grep -Eq '^proprietary_codecs = true$' "$args_file"
grep -Eq '^ffmpeg_branding = "Chrome"$' "$args_file"

temp_root=$(mktemp -d)
trap 'rm -rf -- "$temp_root"' EXIT
tar xzf "$stock_archive" -C "$temp_root"
readonly runtime="$temp_root/$platform"
test -d "$runtime"

rm -rf -- "$runtime/docs" "$runtime/tests"
rm -f -- "$runtime"/compile.* "$runtime"/run.* "$runtime"/java17_check.* \
  "$runtime"/jcef-tests.jar "$runtime"/jcef.jar "$runtime"/gluegen-rt*.jar "$runtime"/jogl-all*.jar

if [[ -d "$cef_distribution/Release" ]]; then
  cp -a "$cef_distribution/Release/." "$runtime/"
else
  echo "CEF distribution has no Release directory: $cef_distribution" >&2
  exit 1
fi
if [[ -d "$cef_distribution/Resources" ]]; then
  cp -a "$cef_distribution/Resources/." "$runtime/"
fi

rm -f -- "$runtime/cefsimple" "$runtime/cefclient" "$runtime/cefsimple.exe" "$runtime/cefclient.exe"
test -f "$runtime/$libcef"
readonly libcef_sha=$(sha256sum "$runtime/$libcef" | awk '{print $1}')
cat > "$runtime/WINLANDCRAFT-CODEC-BUILD.properties" <<EOF
cef.commit=$cef_commit
jcef.commit=$jcef_commit
chromium.version=$chromium_version
gn.proprietary_codecs=true
gn.ffmpeg_branding=Chrome
libcef.sha256=$libcef_sha
EOF

python3 - "$runtime" "$platform" <<'PY'
import hashlib, json, os, pathlib, sys
root = pathlib.Path(sys.argv[1])
platform = sys.argv[2]
manifest = root / 'DISTRIBUTION-MANIFEST.json'
manifest.unlink(missing_ok=True)
files = []
for path in sorted(p for p in root.rglob('*') if p.is_file()):
    data = path.read_bytes()
    files.append({'path': path.relative_to(root).as_posix(), 'size': len(data),
                  'sha256': hashlib.sha256(data).hexdigest()})
manifest.write_text(json.dumps({'archive_root': platform,
                                'cef_api_version': '15100',
                                'cef_commit': '89cd5813e47d84c68e56ced336c2c01b7dc77b8d',
                                'jcef_commit': '2eb4ca2648bda91d1dfed81e9a37ba92e757aff9',
                                'chromium_version': '151.0.7922.34',
                                'distribution_files': files}, indent=2) + '\n')
PY

mkdir -p "$output_dir"
readonly output="$output_dir/$platform.tar.gz"
tar --sort=name --mtime='UTC 2026-08-26' --owner=0 --group=0 --numeric-owner -czf "$output" -C "$temp_root" "$platform"
sha256sum "$output" | sed "s#  .*/#  #" > "$output.sha256"
(cd "$output_dir" && sha256sum -c "$platform.tar.gz.sha256")
