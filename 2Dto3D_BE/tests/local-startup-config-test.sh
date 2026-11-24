#!/usr/bin/env bash

set -Eeuo pipefail

backend_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
source "$backend_dir/dev-env.sh"
system_bash="$(command -v bash)"
test_root="$(mktemp -d)"
trap 'rm -rf -- "$test_root"' EXIT

fixture="$test_root/backend"
fake_bin="$test_root/bin"
capture="$test_root/launch-arguments.txt"
mkdir -p "$fixture/.mvn/wrapper" "$fake_bin"
cp "$backend_dir/dev-env.sh" "$backend_dir/run-local.sh" "$fixture/"
cp "$backend_dir/.mvn/wrapper/maven-wrapper.properties" "$fixture/.mvn/wrapper/"
touch "$fixture/mvnw"

cat > "$fake_bin/java" <<'EOF'
#!/bin/sh
if [ "${1:-}" = "-version" ]; then
    printf 'java version "17.0.12"\n' >&2
    exit 0
fi
exit 1
EOF

cat > "$fake_bin/bash" <<'EOF'
#!/bin/sh
printf '%s\n' "$@" > "$LAUNCH_CAPTURE"
EOF

chmod +x "$fake_bin/java" "$fake_bin/bash"
export HOME="$test_root/home"
export MAVEN_USER_HOME="$test_root/maven-user-home"
export LAUNCH_CAPTURE="$capture"
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS MVNW_REPOURL
unset BACKEND_PORT DB_URL DB_USERNAME DB_PASSWORD JWT_SECRET
export BACKEND_PORT=8181
export DB_URL=jdbc:postgresql://127.0.0.1:55432/local_startup_test
export DB_USERNAME=app_runtime
export DB_PASSWORD=test-password-only
export JWT_SECRET=local-startup-test-secret-with-32-chars

wrapper_home="$(maven_wrapper_home "$fixture")"
mkdir -p "$wrapper_home/bin"
printf '#!/bin/sh\nexit 0\n' > "$wrapper_home/bin/mvn"
chmod +x "$wrapper_home/bin/mvn"
if ! maven_wrapper_is_cached "$fixture"; then
    printf 'Test fixture failed to establish the pinned Maven cache at %s.\n' "$wrapper_home" >&2
    exit 1
fi

if ! PATH="$fake_bin:$PATH" "$system_bash" "$fixture/run-local.sh"; then
    printf 'Local launcher returned failure in the fixture.\n' >&2
    exit 1
fi

if ! grep -Fq -- '-Dspring-boot.run.jvmArguments=-Duser.timezone=Asia/Ho_Chi_Minh' "$capture"; then
    printf 'Local launcher did not set the application JVM timezone.\n' >&2
    exit 1
fi

if ! grep -Fq -- '--spring.profiles.active=local' "$capture" ||
    ! grep -Fq -- '--spring.jpa.hibernate.ddl-auto=validate' "$capture" ||
    ! grep -Fq -- '--spring.flyway.enabled=false' "$capture"; then
    printf 'Local launcher lost its local profile or read-only schema flags.\n' >&2
    exit 1
fi

if ! grep -Fxq 'media.lifecycle.reconciliation-enabled=false' "$backend_dir/src/main/resources/application-local.properties"; then
    printf 'Local profile does not disable media reconciliation.\n' >&2
    exit 1
fi

printf 'Local startup configuration checks passed.\n'
