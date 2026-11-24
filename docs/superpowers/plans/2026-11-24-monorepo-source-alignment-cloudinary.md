# Monorepo Source Alignment and Cloudinary Plan

**Goal:** Carry required local runtime and Cloudinary changes onto the latest official `origin/main` while preserving V21 and the merged private 3MF flow.

**Approach:** Keep the dirty `main` checkout untouched. Work on a clean linked worktree based on `acc093a`; port only loader, local startup, database user, and local Cloudinary configuration changes. Preserve official 3MF behavior and verify it with focused tests and a read-only startup.

**Constraints:** No reset, force push, main merge, secret output/copy, Flyway execution, persistent DB writes, or Cloudinary uploads. TEST/QA provider behavior and V21 schema remain unchanged.

## Tasks

1. Port `.env.local` loader variables, runtime database username, and timezone launcher settings; keep Flyway disabled and local media reconciliation disabled. Test loader precedence and safe diagnostics.
2. Port fail-closed Cloudinary HTTPS configuration and local-only folder policy into the upstream Cloudinary adapter without replacing upstream 3MF logic. Test image `image/upload`, GLB `raw/upload`, 3MF `raw/authenticated`, public URL resolution, and private 3MF response behavior.
3. Use the original `.env.local` without copying it to validate source loading and start the Backend with the default local launcher. Confirm PostgreSQL V21 compatibility and Hibernate `validate` without migration or persistent writes.
4. Verify account permissions/quota status without uploading; inspect the final branch diff and leave it uncommitted for review.

## Review focus

- Cloudinary API key display name is not the API Key value; do not use credentials until the owner verifies the matching pair.
- Dynamic folder assignment must not alter `public_id` or V17 database constraints.
- 3MF must remain `raw/authenticated` and absent from public product URL responses.
- Missing local credentials must fail closed while TEST/QA remains isolated.
- Startup validation must not execute Flyway or local media reconciliation.
