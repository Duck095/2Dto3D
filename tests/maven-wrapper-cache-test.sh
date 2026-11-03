#!/usr/bin/env bash

set -Eeuo pipefail

backend_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
source "$backend_dir/dev-env.sh"

test_root="$(mktemp -d)"
trap 'rm -rf -- "$test_root"' EXIT
export MAVEN_USER_HOME="$test_root/maven-user-home"
export HOME="$test_root/ignored-home"
unset MVNW_REPOURL || true

distribution_url="$(sed -n 's/^distributionUrl=//p' "$backend_dir/.mvn/wrapper/maven-wrapper.properties" | tr -d '\r[:space:]')"
distribution_archive="${distribution_url##*/}"
distribution_dir="${distribution_archive%.zip}"
distribution_dir="${distribution_dir%-bin}"
expected_hash="$(maven_wrapper_hash "$distribution_url")"
expected_home="$MAVEN_USER_HOME/wrapper/dists/$distribution_dir/$expected_hash"
actual_home="$(maven_wrapper_home "$backend_dir")"

if [[ "$actual_home" != "$expected_home" ]]; then
    printf 'Expected Maven Wrapper home %s, got %s.\n' "$expected_home" "$actual_home" >&2
    exit 1
fi

mkdir -p "$expected_home/bin"
printf '#!/bin/sh\nexit 0\n' > "$expected_home/bin/mvn"
chmod +x "$expected_home/bin/mvn"
if ! maven_wrapper_is_cached "$backend_dir"; then
    printf 'Launcher did not recognize the Maven distribution at the Maven Wrapper cache path.\n' >&2
    exit 1
fi

rm -f "$expected_home/bin/mvn"
mkdir -p "$expected_home/$distribution_dir/bin"
printf '#!/bin/sh\nexit 0\n' > "$expected_home/$distribution_dir/bin/mvn"
chmod +x "$expected_home/$distribution_dir/bin/mvn"
if maven_wrapper_is_cached "$backend_dir"; then
    printf 'Launcher incorrectly accepted the obsolete extra distribution directory.\n' >&2
    exit 1
fi

printf 'Maven Wrapper cache path check passed.\n'
