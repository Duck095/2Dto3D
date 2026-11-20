# Product Media and 3D Asset Management Design

Date: 2026-10-30  
Workspace: `2Dto3D_BE`  
Status: Revised after SPEC_REVIEW; awaiting final written-spec approval before implementation planning.

## Goal

Add a production-oriented media foundation for designer product creation. New image and GLB uploads must be validated before provider calls, carry trusted Cloudinary metadata through persistence and URL resolution, and be compensated safely when a later upload or database save fails. Preserve legacy path fields and records, TASK 01 catalog responses, existing AI model ownership behavior, and the current AI upload pipeline.

## Current-state findings

- `CloudinaryServiceImpl` uploads multipart content using `getBytes()`, returns only `public_id`, has no deletion method, and uses Cloudinary's default upload delivery type.
- `Model3dController` uploads the model and images before calling the transactional model service. A later upload or database failure leaves earlier assets behind.
- `Model3d.file_path_3d` and `ModelImage.image_path` preserve legacy identifiers but do not retain provider asset identity, resource type, format, or trusted secure URL.
- `ModelUrlResolver` constructs raw URLs by replacing a URL segment and returns an empty string for missing paths. `ProductServiceImpl` constructs image URLs from a configured base URL.
- Designer POST authorization is already restricted to ADMIN/DESIGNER. The controller overwrites submitted owner IDs with the authenticated principal. AI model reads are owner-checked by `Model3dServiceImpl`.
- AI and designer uploads currently share the same Cloudinary service. AI uploads use public upload delivery, so the file URL may be fetchable outside the owner-checked API. TASK 02 will not broaden this access or change the AI generation pipeline.
- There is no Flyway/Liquibase migration history or Cloudinary upload test suite. The application currently relies on Hibernate `ddl-auto=update` for schema evolution.
- The worktree contains extensive uncommitted changes. A targeted persistent preflight backup, status snapshot, and tracked diff with verified SHA-256 checksums are available at `D:\2Dto3D\.codex-backups\2Dto3D_BE\TASK02-preflight-20261030`.

## Architecture

### Storage boundary and metadata

Introduce a provider-neutral `MediaStorageService` and typed upload/deletion results. `CloudinaryMediaStorageService` is the Cloudinary adapter. A `StoredMedia`/`MediaAsset` record carries:

- generated internal database ID and operation ID;
- provider name and Cloudinary `asset_id`;
- configured Cloudinary cloud name, so asset identity is scoped to the provider environment;
- server-generated public ID (never supplied by the client);
- media kind (`IMAGE` or `MODEL_3D`), Cloudinary resource type, and delivery type;
- actual format, byte count, provider version, and HTTPS `secure_url`;
- lifecycle state, timestamps, retry count, and a sanitized last failure category.

New uploads use secure URLs and metadata returned by Cloudinary. Validate required response fields, expected resource type, allowed format, and HTTPS URL before treating the provider result as usable. Resolve future delivery URLs from persisted metadata; use the Cloudinary SDK URL API for compatible legacy identifier resolution. Never synthesize a URL when required metadata is absent.

### Schema and migration adoption

Add versioned Flyway migrations. `V2__media_asset.sql` creates `media_asset` and adds nullable `media_asset_id` foreign keys to `model_3d` and `model_images`. Keep `file_path_3d` and `image_path` unchanged and populated with the same provider public IDs used by existing records. For new rows, `media_asset` is authoritative for provider metadata, lifecycle state, and catalog delivery URLs. Add uniqueness constraints for each model/image asset reference and provider identity, foreign keys with `RESTRICT` semantics, and indexes for provider identity and lifecycle recovery scans. Nullable references leave all legacy rows valid.

The current database is Hibernate-managed (`ddl-auto=update`) and has no recorded migration baseline. Do not infer or claim a complete PostgreSQL base schema from JPA entities or H2. Establish a reviewed `V1__legacy_schema_baseline.sql` in a separate prerequisite task from an inspected, schema-only PostgreSQL export and verify it against a staging copy. TASK 02's media migration is `V2` and depends on that artifact. This is a release gate for fresh PostgreSQL databases: provision them by explicitly running V1 and V2 before starting the application. Do not use Hibernate schema creation or `ddl-auto=update` as the production initialization path.

For an existing Hibernate-managed database, first take a restorable backup and compare its schema with the reviewed V1 baseline on a non-production copy. An operator explicitly adopts a matching database with `flyway baseline -baselineVersion=1`; automatic baselining stays disabled. The operator then explicitly runs `flyway migrate` as a separate release step. A mismatch blocks adoption until reconciled. No baseline or migration is run against production by this task.

After the migration prerequisite is available, use `spring.jpa.hibernate.ddl-auto=validate`; Flyway is the only schema evolution mechanism. Keep migration execution out of normal API startup: the API runtime has Flyway migration disabled, and a separately approved deployment/migration job runs Flyway with `baseline-on-migrate=false` and `clean-disabled=true`. The job must fail if the history table/baseline is missing. For a fresh test database, apply V1 and V2 explicitly before application startup; migration tests should run against a PostgreSQL test instance. H2 unit/integration tests may disable Flyway and use JPA schema creation for non-migration behavior. PostgreSQL behavior remains a release gate if a safe test database is unavailable.

### Upload orchestration and persistence boundary

Keep file validation, provider I/O, and transactional model persistence separate:

1. The authenticated designer request supplies metadata and multipart files. The service derives the owner from `UserPrincipal`; submitted IDs and paths are overwritten or rejected and never trusted.
2. A validator checks all metadata and files before any provider call.
3. A short transaction creates `media_asset` upload-intent rows in `PENDING_UPLOAD`, each with a unique operation ID, server-generated public ID, expected provider/resource/delivery types, lease owner/token, and lease timestamps. No client filename is used as identity.
4. The orchestration service claims each intent with a compare-and-set transition to `UPLOADING`, then performs Cloudinary I/O outside database transactions. The provider has bounded connect/read/request timeouts. The upload lease is longer than the maximum provider request timeout plus a safety margin and is renewed while the request is active. Only the current lease token may persist the upload result and transition to `READY`.
5. A separate transactional persistence service locks all operation asset rows, requires `READY`, saves the pending `Model3d` and `ModelImage` rows with their nullable media references, and changes the same rows to `ATTACHED` in one transaction. New models retain the existing default `PENDING` status. Attachment and cleanup therefore serialize on the media rows; foreign keys also restrict removal of referenced rows.
6. On upload or persistence failure, the request owner transitions only its unreferenced assets to `CLEANUP_PENDING`. Cleanup claims a row atomically as `CLEANUP_LEASED` using a lease token and a row lock/conditional update (`SKIP LOCKED` where supported). It checks references before claiming and again before the provider call. The provider call occurs outside a database transaction and uses the exact stored resource type, delivery type, and public ID. A missing provider asset is idempotent success.

Lifecycle states and allowed transitions:

| State | Allowed next state | Rule |
| --- | --- | --- |
| `PENDING_UPLOAD` | `UPLOADING`, `CLEANUP_PENDING` | Claim with a request lease before upload; cleanup only after the lease expires plus the safety grace period. |
| `UPLOADING` | `READY`, `CLEANUP_PENDING` | The matching unexpired upload token must own the transition. Persist validated provider metadata before `READY`. |
| `READY` | `ATTACHED`, `CLEANUP_PENDING` | Attachment locks and verifies the row; abort may request cleanup only after proving it is unreferenced. |
| `ATTACHED` | none in TASK 02 | Reconciler and compensation never delete or detach it. Replacement/deletion belongs to a later lifecycle design. |
| `CLEANUP_PENDING` | `CLEANUP_LEASED` | Atomic lease claim only when unreferenced and older than the configured safety age. |
| `CLEANUP_LEASED` | `DELETED`, `CLEANUP_PENDING` | On provider success/not-found mark `DELETED`; on failure or expired cleanup lease return to retry state with bounded backoff. |
| `DELETED` | none | Terminal tombstone retained for audit/idempotency. |

The reconciler ignores every unexpired upload lease. A stale upload is eligible only after both the upload lease and a configurable grace period have elapsed; the grace period exceeds the provider timeout and clock-skew allowance. A late request cannot advance state after losing its lease token. Cleanup and attachment use the same row lock/state predicate, so a row already claimed for cleanup cannot become attached. If the database is unavailable or the commit result is ambiguous, do not call Cloudinary destroy. Read the primary database in a fresh transaction when it is available: if a reference or `ATTACHED` state exists, preserve the asset; if the query cannot establish safety, leave it pending. If Cloudinary destroy succeeds but the database state update fails, retrying destroy is safe and idempotent. Persist bounded retry state and sanitized failure details.

Durable intents and leases recover a process crash after the intended public ID is recorded. A crash after Cloudinary accepts an upload but before its response is persisted remains discoverable by that intent's server-generated public ID. Reconciliation is delayed until the timeout/grace window prevents racing an active upload. Provider/database outages can still delay cleanup; immediate orphan-free behavior cannot be guaranteed while either dependency is unavailable.

Do not add public media deletion or replacement endpoints. The reconciler may delete only an asset created by an upload intent owned by this workflow and proven unreferenced.

## Validation policy

Limits are configuration properties, not controller constants:

- JPEG, PNG, and WEBP images; default maximum 10 MiB each and at most 8 images per request.
- Product model `.glb`; default maximum 50 MiB.
- Multipart per-file and request limits align with the configured maximum total upload plus bounded overhead. Configure temporary multipart storage to avoid large heap buffering.
- Reject null, empty, oversized, unsupported extension, mismatched MIME, and malformed content before calling Cloudinary. Do not accept SVG, HTML, executable, or arbitrary formats for product images.
- Validate decoded image content rather than trusting name or `Content-Type`; use the JDK readers for JPEG/PNG and an explicit WebP ImageIO reader dependency. Apply a configurable decoded-pixel ceiling to reduce decompression-bomb risk.
- Validate GLB without loading the whole model into heap: check the 12-byte header, `glTF` magic, version 2, and declared byte length against the multipart size. Do not convert STL/OBJ. Preserve the existing AI/designer legacy flow separately where it is still used; the new product designer upload accepts GLB only.
- The first accepted image is primary; all remaining images are non-primary. Ignore any client-supplied image paths or primary flags.

## API and URL behavior

- Keep `/api/designer-models` as the upload endpoint and retain its multipart parts and response DTO shape. `ModelResponse.filePath3d` and image `imagePath` keep their existing meaning and value semantics: Cloudinary public IDs, not delivery URLs. Keep the legacy path columns populated with those public IDs for old and new records. Do not silently replace them with HTTPS URLs.
- Do not change TASK 01 `GET /api/products` or `GET /api/products/{id}` JSON field names or filtering/security behavior. For new records, the catalog obtains thumbnail, image, and model delivery URLs only from the linked `media_asset.secure_url` after validating its provider/resource metadata. For legacy rows with no media reference, use the legacy resolver against the stored public ID. Missing or invalid media metadata yields `null`, not a fabricated URL.
- Do not add public delete endpoints or new public catalog paths to AI records.
- Map malformed upload errors to consistent JSON without stack traces or provider details: 400 for malformed/missing files, 413 for configured size limits, 415 for unsupported/mismatched formats, and 502/503 for Cloudinary failures. Cleanup failures are logged with operation/asset identifiers and lifecycle state, but never replace the original request error.

## Security and privacy

- Upload routes remain authenticated and role-restricted. Owner comes only from the JWT principal.
- Validate everything before Cloudinary calls. Use server-generated, unique IDs and overwrite disabled. Do not accept client paths, URLs, public IDs, filenames as identity, or remote fetch URLs.
- Cloudinary credentials remain environment-backed. Do not add or expose secrets.
- New product designer media is public only as required by the existing public catalog. The new media service is not used by AI uploads. Existing AI raw assets may already have public delivery URLs despite owner-protected API reads; this is an existing exposure risk to report and defer to a separate access-mode migration decision.
- No content or credentials are logged. Cleanup logs contain operation ID, internal asset ID, resource type, public ID, attempt, and sanitized failure category.

## Cloudinary identity and GLB boundary

- Generate a cryptographically random server-side UUID per asset under configured product folders. Image IDs are extensionless, use `resource_type=image` and delivery `type=upload`, and disable overwrite and filename reuse.
- GLB IDs include the required raw extension, for example `product-models/<server-uuid>.glb`; upload with `resource_type=raw`, delivery `type=upload`, `overwrite=false`, and `use_filename=false`. Never use the client filename as the public ID.
- Require the returned `public_id` to equal the intended ID, `resource_type` and delivery type to match the request, `format` to match validated content (`glb` for models; normalized detected image format for images), and nonempty Cloudinary `asset_id`, version/byte metadata, and HTTPS `secure_url` on the configured Cloudinary cloud host. Reject incomplete or inconsistent responses. Record the exact accepted values and use those exact resource type, delivery type, and public ID for cleanup. Do not guess deletion defaults or infer them from the URL.
- GLB validation covers the container header only: at least 12 bytes, ASCII magic `glTF`, version 2, declared total length equal to actual multipart byte length, and the configured size bound. Header reads are streaming. It does not parse every chunk, validate glTF JSON/accessors/materials, scan embedded content for malware, or guarantee safety of downstream viewers; those are outside this upload-format gate.

## Tests and verification

- Validator tests cover valid JPEG/PNG/WEBP, extension/MIME/signature mismatches, empty/oversized content, image count, WebP decoder availability, and GLB magic/version/length.
- Storage adapter tests mock Cloudinary upload/destroy responses, including missing/invalid metadata, image versus raw type, exact GLB extension, and destroy retries. No default test contacts Cloudinary.
- Persistence tests cover additive nullable legacy references, media row relations, uniqueness/restrict behavior, and migration adoption against PostgreSQL if a safe test DB is available.
- Orchestration tests cover successful creation, second-image failure, persistence/commit failure, reference-safe compensation, idempotent cleanup, cleanup failure preserving the original error, and stale intent reconciliation.
- Security and regression tests cover owner derivation, anonymous/unauthorized upload rejection, existing AI owner checks, unchanged TASK 01 product contract, legacy URL fallback, missing media nulls, pending designer model status, and no AI catalog exposure.
- Run focused media tests, the complete `mvn clean test`, and `git diff --check`. Do not claim real Cloudinary or PostgreSQL verification without a safe configured environment.

## Risks and explicit limits

- Existing schemas have no migration history. Baseline adoption is an operator-controlled release action; this task will not auto-baseline or run against production.
- A reviewed full V1 schema migration is a prerequisite for fresh PostgreSQL initialization and for a defensible adoption comparison. Until that separate prerequisite is complete, fresh-database startup and production migration are blocked release gates; do not substitute Hibernate `ddl-auto=update`.
- Cloudinary response URLs are only proven syntactically in mocked/unit tests unless a separate test account is available. A URL that looks valid is not evidence of a successful browser download.
- Cleanup is best effort during provider/database outages. Durable intents and reconciliation reduce orphan risk but cannot guarantee immediate cleanup while dependencies remain unavailable.
- Existing public AI Cloudinary assets are not made private here; changing access mode can break existing AI consumers and needs a separate migration/authorization design.
- Existing hardcoded payment/JWT values were found in `application.properties`. They predate TASK 02 and are outside this task's media scope; they will not be repeated or changed by the media implementation. They should be moved to managed environment secrets and rotated if active.

## Scope exclusions

No TASK 03 replacement/delete API, no AI generation changes, no order/payment/pricing changes, no admin product lifecycle workflow, and no production database or real Cloudinary mutation from this workspace.
