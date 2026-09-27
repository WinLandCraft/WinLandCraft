#!/usr/bin/env bash
# Usage: ./dev.sh build   or   ./dev.sh runClient
set -euo pipefail

project_dir=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)

java_root=${JAVA_HOME:-}
if [[ -n "$java_root" && ! -x "$java_root/bin/javac" ]]; then
  printf 'JAVA_HOME does not contain an executable bin/javac: %s\n' "$java_root" >&2
  exit 1
fi

if [[ -z "$java_root" ]] && command -v javac >/dev/null 2>&1; then
  javac_path=$(readlink -f "$(command -v javac)")
  java_root=$(dirname -- "$(dirname -- "$javac_path")")
fi

if [[ -z "$java_root" ]]; then
  for candidate in /usr/lib/jvm/temurin-25-jdk /usr/lib/jvm/java-25-openjdk /usr/lib/jvm/java-25-openjdk-* /usr/lib/jvm/temurin-25-*; do
    if [[ -x "$candidate/bin/javac" ]]; then
      java_root=$candidate
      break
    fi
  done
fi

if [[ -z "$java_root" ]]; then
  printf '%s\n' 'JDK 25 was not found. Install a JDK 25 package or set JAVA_HOME to its directory.' >&2
  exit 1
fi

javac_version=$("$java_root/bin/javac" -version 2>&1)
if [[ ! "$javac_version" =~ ^javac[[:space:]]+25([.]|$) ]]; then
  printf 'WinLandCraft requires JDK 25; found %s at %s\n' "$javac_version" "$java_root" >&2
  exit 1
fi

export JAVA_HOME=$java_root
export GRADLE_USER_HOME="$project_dir/.gradle-user-home"
cd -- "$project_dir"

if (($# == 0)); then
  set -- build
fi
exec sh "$project_dir/gradlew" "$@"
