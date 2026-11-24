#!/usr/bin/env bash

set -Eeuo pipefail

backend_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
source "$backend_dir/dev-env.sh"

test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT

assert_eq() {
    local name="$1" expected="$2" actual="$3"
    if [[ "$actual" != "$expected" ]]; then
        printf 'FAIL: %s did not match expected value.\n' "$name" >&2
        exit 1
    fi
}

assert_unset() {
    local name="$1"
    if [[ -v $name ]]; then
        printf 'FAIL: %s should not have been loaded.\n' "$name" >&2
        exit 1
    fi
}

unset BACKEND_PORT DB_URL DB_USERNAME DB_PASSWORD JWT_SECRET CLOUD_NAME API_KEY API_SECRET
cat > "$test_dir/.env.local" <<'EOF'
BACKEND_PORT=8181
DB_URL=jdbc:postgresql://127.0.0.1:55432/morphia3d_prod_prep
DB_USERNAME=app_runtime
DB_PASSWORD=fake-db-password-for-test
JWT_SECRET=fake-jwt-secret-for-test-only
CLOUD_NAME=zsxcxwta
API_KEY=fake-cloudinary-key-for-test
API_SECRET=fake-cloudinary-secret-for-test
EOF
load_local_env "$test_dir"
assert_eq BACKEND_PORT 8181 "$BACKEND_PORT"
assert_eq DB_URL 'jdbc:postgresql://127.0.0.1:55432/morphia3d_prod_prep' "$DB_URL"
assert_eq DB_USERNAME app_runtime "$DB_USERNAME"
assert_eq DB_PASSWORD fake-db-password-for-test "$DB_PASSWORD"
assert_eq JWT_SECRET fake-jwt-secret-for-test-only "$JWT_SECRET"
assert_eq CLOUD_NAME zsxcxwta "$CLOUD_NAME"
assert_eq API_KEY fake-cloudinary-key-for-test "$API_KEY"
assert_eq API_SECRET fake-cloudinary-secret-for-test "$API_SECRET"

unset BACKEND_PORT DB_URL DB_USERNAME DB_PASSWORD JWT_SECRET CLOUD_NAME API_KEY API_SECRET
cat > "$test_dir/.env.local" <<'EOF'
DB_USERNAME=file-user
DB_PASSWORD=file-password
JWT_SECRET=file-jwt-secret
CLOUD_NAME=file-cloud
API_KEY=file-key
API_SECRET=file-secret
EOF
export DB_USERNAME=process-user DB_PASSWORD=process-password JWT_SECRET=process-jwt-secret
export CLOUD_NAME=process-cloud API_KEY=process-key API_SECRET=process-secret
load_local_env "$test_dir"
assert_eq DB_USERNAME process-user "$DB_USERNAME"
assert_eq DB_PASSWORD process-password "$DB_PASSWORD"
assert_eq JWT_SECRET process-jwt-secret "$JWT_SECRET"
assert_eq CLOUD_NAME process-cloud "$CLOUD_NAME"
assert_eq API_KEY process-key "$API_KEY"
assert_eq API_SECRET process-secret "$API_SECRET"

unset BACKEND_PORT DB_URL DB_USERNAME DB_PASSWORD JWT_SECRET CLOUD_NAME API_KEY API_SECRET
cat > "$test_dir/.env.local" <<'EOF'
DB_PASSWORD=" space #hash$dollar=semi;bang!\backslash "
API_SECRET=" hash$dollar=semi;bang!\backslash "
EOF
load_local_env "$test_dir"
assert_eq 'DB_PASSWORD special characters' ' space #hash$dollar=semi;bang!\backslash ' "$DB_PASSWORD"
assert_eq 'API_SECRET special characters' ' hash$dollar=semi;bang!\backslash ' "$API_SECRET"

unset BACKEND_PORT DB_URL DB_USERNAME DB_PASSWORD JWT_SECRET CLOUD_NAME API_KEY API_SECRET
DB_URL='jdbc:postgresql://127.0.0.1:55432/morphia3d_prod_prep'
DB_USERNAME=app_runtime
DB_PASSWORD=fake-db-password-for-test
JWT_SECRET=01234567890123456789012345678901
validate_local_database_config
DB_USERNAME=postgres
if validate_local_database_config 2>/dev/null; then
    printf 'FAIL: the prep database should reject the postgres account.\n' >&2
    exit 1
fi
DB_USERNAME=app_runtime
unset DB_USERNAME
if validate_local_database_config 2>/dev/null; then
    printf 'FAIL: local startup should require an explicit database account.\n' >&2
    exit 1
fi

unset BACKEND_PORT DB_URL DB_USERNAME DB_PASSWORD JWT_SECRET CLOUD_NAME API_KEY API_SECRET
cat > "$test_dir/.env.local" <<'EOF'
UNKNOWN_SECRET=must-not-load
EOF
if load_local_env "$test_dir" 2>/dev/null; then
    printf 'FAIL: unsupported variables should be rejected.\n' >&2
    exit 1
fi
assert_unset UNKNOWN_SECRET

printf 'PASS: local env loader allowlist and precedence checks passed.\n'
