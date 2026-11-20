#!/usr/bin/env bash

set -Eeuo pipefail

backend_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source "$backend_dir/dev-env.sh"

load_local_env "$backend_dir"
BACKEND_PORT="${BACKEND_PORT:-8080}"
export BACKEND_PORT
validate_backend_port
validate_local_database_config

if ! command -v java >/dev/null 2>&1; then
    printf 'Java 17 or newer is required, but java was not found on PATH.\n' >&2
    exit 1
fi

java_version="$(java -version 2>&1 | sed -n '1p')"
java_major="$(printf '%s\n' "$java_version" | sed -n 's/.*version "\([0-9][0-9]*\)\([."].*\)/\1/p')"
if [[ ! "$java_major" =~ ^[0-9]+$ ]] || ((java_major < 17)); then
    printf 'Java 17 or newer is required. Detected runtime does not meet that requirement.\n' >&2
    exit 1
fi

if [[ ! -f "$backend_dir/mvnw" ]]; then
    printf 'Maven Wrapper is missing: %s/mvnw\n' "$backend_dir" >&2
    exit 1
fi

if [[ -n "${MVNW_REPOURL:-}" ]]; then
    printf 'MVNW_REPOURL overrides the pinned Maven cache location; unset it before using the local launcher.\n' >&2
    exit 1
fi

if ! maven_wrapper_is_cached "$backend_dir"; then
    printf 'Pinned Maven distribution is not cached or its cache home could not be resolved. The local launcher will not download software; bootstrap the Maven Wrapper explicitly, then retry.\n' >&2
    exit 1
fi

cd "$backend_dir"
exec bash ./mvnw spring-boot:run \
    "-Dspring-boot.run.arguments=--server.port=$BACKEND_PORT --spring.profiles.active=local --spring.flyway.enabled=false --spring.jpa.hibernate.ddl-auto=validate"
