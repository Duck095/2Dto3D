# Product Media and 3D Asset Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Each task follows RED → GREEN and ends with path-limited verification.

**Goal:** Add safe, validated designer image/GLB uploads backed by durable Cloudinary asset metadata, reference-safe compensation, and legacy-compatible product URL resolution.

**Architecture:** Add a provider-neutral storage port, a Cloudinary adapter, a `media_asset` persistence/lifecycle model, a validator, an upload orchestrator, and a separate transactional model persistence service. Flyway owns the additive V2 media schema; explicit V1 schema creation/adoption remains a release prerequisite and no production migration is run here.

**Tech Stack:** Java 17, Spring Boot 4.1, Spring Data JPA, Flyway, Cloudinary Java SDK 1.38.0, Maven, JUnit 5, Mockito, H2; optional PostgreSQL Testcontainers when Docker is available.

**Spec:** `docs/superpowers/specs/2026-10-30-product-media-design.md`

## Global Constraints

- Accept product images as JPEG, PNG, or WEBP; default maximum 10 MiB each and at most 8 images per request.
- Accept product model `.glb`; default maximum 50 MiB; validate `glTF` magic, version 2, and declared total length against actual bytes.
- New image/model upload identifiers are server-generated; image public IDs are extensionless, raw GLB IDs end in `.glb`, and Cloudinary overwrite is disabled.
- Persist provider, configured cloud name, asset ID, public ID, media kind, resource type, delivery type, format, byte count, version, secure URL, lifecycle state, operation ID, lease data, retries, and sanitized error category.
- Preserve `Model3d.filePath3d`, `ModelImage.imagePath`, and their public-ID semantics. New catalog URLs use linked `media_asset`; legacy rows retain their current resolver fallback. Do not change TASK 01 response JSON.
- Model and image references to media are nullable and use restrictive foreign keys. Never delete when references or transaction outcome are uncertain.
- Keep provider HTTP calls outside database transactions. Keep the existing AI upload/generation pipeline separate and do not add public AI media behavior.
- Flyway automatic baselining is disabled; normal API startup does not run migrations; Hibernate uses `ddl-auto=validate` after migration prerequisite completion.
- V1 full legacy schema migration and safe PostgreSQL verification remain separate release gates. Do not execute production migrations or real Cloudinary calls.
- Preserve existing uncommitted changes and the verified backup at `D:\2Dto3D\.codex-backups\2Dto3D_BE\TASK02-preflight-20261030`.
- Restrict edits to product media, model/image persistence, required config/dependencies, focused tests, and migration documentation. Do not edit order, payment, pricing, or unrelated modules.

## Review Focus

1. A Cloudinary upload returns an unexpected `public_id`, `resource_type`, `format`, `asset_id`, or URL host; reject it and clean only the request-owned intended identity.
2. A request or provider call runs past its upload lease; the reconciler must wait past the provider timeout and safety grace, and stale lease holders must not attach or update the asset.
3. Database commit returns an ambiguous failure or the database is unreachable; do not delete until a fresh primary read proves the asset is unreferenced.
4. A legacy model/image has no media reference or has a null/blank identifier; preserve public-ID DTO semantics and return null from catalog URL fields rather than fabricating a URL.
5. Concurrent cleanup workers or attachment race on one asset; atomic claim/state checks must allow at most one destructive claim and prevent attachment after cleanup claim.

---

## File Structure

- `src/main/java/com/exe/morphia3d/entity/MediaAsset.java` and `enums/MediaAssetState.java`, `MediaKind.java`: provider metadata and durable lifecycle state.
- `src/main/java/com/exe/morphia3d/entity/Model3d.java`, `entity/ModelImage.java`: nullable media references; legacy path fields stay unchanged.
- `src/main/java/com/exe/morphia3d/repository/MediaAssetRepository.java`: lookup, lock, and atomic lease claims.
- `src/main/resources/db/migration/V2__media_asset.sql`: additive PostgreSQL migration, assumes operator-adopted V1 schema.
- `service/MediaStorageService.java`, `service/MediaUploadResult.java`, `service/MediaUploadIntent.java`, `service/impl/CloudinaryMediaStorageService.java`: provider-neutral contract and Cloudinary integration.
- `service/MediaValidationService.java`, `service/ValidatedMedia.java`, `config/MediaUploadProperties.java`: streaming file checks and configurable limits.
- `service/MediaAssetLifecycleService.java`, `service/MediaAssetReconciliationService.java`: transactional state changes, atomic claims, reference checks, and bounded retries.
- `service/DesignerModelUploadService.java`, `service/impl/DesignerModelPersistenceService.java`: non-transactional orchestration and isolated transactional persistence.
- `utils/MediaUrlResolver.java`, `service/impl/ProductServiceImpl.java`: saved URL use and legacy path fallback without changing product response DTOs.
- `controller/Model3dController.java`, focused media exception handler/config: route wiring and consistent upload errors; existing AI route continues using `CloudinaryService`.
- `src/test/java/com/exe/morphia3d/...`: validator, provider, lifecycle, persistence, controller/security, migration, and catalog regression tests.
- `src/test/resources/application-test.properties`, `src/main/resources/application.properties`, `pom.xml`: test/runtime properties and Flyway/WebP/PostgreSQL test dependencies.
- `docs/superpowers/plans/2026-10-30-product-media.md`: task ledger target; implementation progress is recorded in `.superpowers/sdd/2026-10-30-product-media/progress.md`.

## Interfaces

- `MediaStorageService.upload(MediaUploadIntent intent, ValidatedMedia media) -> MediaUploadResult`: uploads only the already validated stream using the intent's server-generated identity.
- `MediaStorageService.delete(MediaAssetIdentity identity) -> DeleteResult`: destroys using stored provider, cloud name, resource type, delivery type, and public ID; `NOT_FOUND` is idempotent success.
- `MediaUploadIntent` carries internal asset ID, operation ID, lease token, intended public ID, media kind, resource type, and delivery type.
- `MediaUploadResult` carries provider, cloud name, returned asset ID/public ID, resource and delivery types, format, byte count, version, and secure URL.
- `MediaAsset` states are `PENDING_UPLOAD`, `UPLOADING`, `READY`, `ATTACHED`, `CLEANUP_PENDING`, `CLEANUP_LEASED`, and terminal `DELETED`. Transitions follow the approved design table.
- `MediaValidationService.validateImages(List<MultipartFile>) -> List<ValidatedMedia>` and `validateGlb(MultipartFile) -> ValidatedMedia` complete before upload-intent creation.
- `DesignerModelUploadService.create(UserPrincipal principal, DesignerModelRequest metadata, List<MultipartFile> images, MultipartFile model) -> ModelResponse` validates, creates intents, uploads, and invokes persistence/compensation; it contains no database transaction around provider calls.
- `DesignerModelPersistenceService.persist(UserPrincipal principal, DesignerModelRequest metadata, List<Long> assetIds) -> ModelResponse` runs one database transaction and attaches only `READY` assets.
- `MediaAssetLifecycleService.claimUpload`, `recordUpload`, `attach`, `requestCleanup`, `claimCleanup`, and `finishCleanup` use lease tokens and conditional state transitions; any call that cannot prove safe ownership/reference state fails closed.

## Task 1: Flyway Media Schema and JPA Metadata

**Files:** create media entity/state/kind/repository and `V2__media_asset.sql`; modify `Model3d`, `ModelImage`, `pom.xml`, `application.properties`, test profile; create persistence/migration tests.

**Interfaces:** produces `MediaAsset`, lifecycle enums, repository, and nullable model/image associations for later tasks.

- [ ] Write an H2 JPA test persisting a legacy model/image with null media references and a new model/image with complete media metadata.
- [ ] Run the focused test and verify RED because media entity/references are missing.
- [ ] Add the entity and nullable associations; assert metadata round trips and legacy rows remain valid.
- [ ] Add database uniqueness and restrictive reference constraints, operation ID uniqueness, provider identity index, lifecycle/created index, and V2 SQL. Add Flyway core/PostgreSQL support and explicit migration-job configuration; keep app startup migration disabled and set runtime Hibernate mode to validate. Document V1 prerequisite and operator-only baseline/migrate commands in `docs/database/product-media-migration.md`.
- [ ] Add a PostgreSQL migration integration test that runs V2 over a minimal V1 fixture using Testcontainers with `disabledWithoutDocker=true`; it must prove nullable legacy compatibility, references, constraints, and indexes. Add an H2 JPA persistence test for default CI when Docker is unavailable.
- [ ] Run focused entity tests and migration tests. Record a Testcontainers skip as an unverified PostgreSQL release gate; do not claim H2 proves PostgreSQL SQL compatibility.
- [ ] Commit only Task 1 files with `feat: add media asset persistence schema`.

## Task 2: Image and GLB Validation

**Files:** create validation service/result/properties; modify `pom.xml` for a WebP ImageIO reader and multipart bounds; create validator tests and small valid fixtures.

**Interfaces:** consumes limits from Task 1 configuration; produces `ValidatedMedia` for the storage port.

- [ ] Write tests `acceptsValidJpegPngAndWebp`, `rejectsImageNameMimeSignatureMismatch`, `rejectsEmptyAndOversizedImages`, `rejectsMoreThanEightImages`, `rejectsMalformedImageDecode`, `acceptsValidGlbHeader`, `rejectsGlbWrongMagicVersionOrDeclaredLength`, and `rejectsNonGlbModel`.
- [ ] Run each focused validator test and verify RED on missing validator behavior.
- [ ] Implement property-backed defaults (10 MiB/image, 8 images, 50 MiB/model, request cap for 8 images + model + overhead, decoded-pixel cap); check extension, MIME, magic/content decode and file size before any provider call.
- [ ] Read GLB header/chunk length from a bounded stream; require at least 12 bytes, `glTF`, version 2, declared length equals actual size, and max size. Do not load the whole GLB into heap or convert STL/OBJ.
- [ ] Run validator tests to GREEN and confirm valid WebP decodes through the configured provider.
- [ ] Commit only Task 2 files with `feat: validate product image and glb uploads`.

## Task 3: Cloudinary Adapter and Trusted Metadata

**Files:** create provider-neutral storage interfaces/results and `CloudinaryMediaStorageService`; modify `CloudinaryConfig`; create adapter tests.

**Interfaces:** consumes validated media and `MediaUploadIntent`; produces strict `MediaUploadResult`; delete consumes exact `MediaAssetIdentity`.

- [ ] Write mocked-SDK tests `uploadsImageWithImageUploadType`, `uploadsGlbWithRawTypeAndGlbPublicId`, `rejectsMismatchedCloudinaryIdentity`, `rejectsMissingSecureUrlOrAssetId`, `deletesWithExactStoredResourceAndDeliveryTypes`, and `treatsProviderNotFoundAsIdempotentDelete`.
- [ ] Run focused adapter tests and verify RED.
- [ ] Implement upload options with server-generated IDs, image IDs without suffix, raw model IDs ending `.glb`, `overwrite=false`, no client filename use, resource type `image`/`raw`, and delivery type `upload`.
- [ ] Verify returned public ID, cloud name, asset ID, resource type, delivery type, format, byte count, version, HTTPS Cloudinary host, and GLB extension before accepting metadata. Configure bounded connect/read/request timeouts and streaming input where SDK support allows.
- [ ] Implement destroy with exact saved identity; sanitize provider exceptions and never expose API key/secret or raw SDK responses.
- [ ] Run mocked provider tests to GREEN; no test may call Cloudinary network endpoints.
- [ ] Commit only Task 3 files with `feat: add cloudinary media storage adapter`.

## Task 4: Durable Lifecycle, Atomic Leases, and Reconciliation

**Files:** implement lifecycle service/repository queries and reconciler; create state-transition, concurrency, and cleanup tests.

**Interfaces:** consumes Task 1 repository and Task 3 identity/delete contract; exposes upload/cleanup lease operations to Task 5.

- [ ] Write transition tests for every allowed transition and rejection of stale/wrong lease tokens; verify ATTACHED and DELETED cannot be claimed for cleanup.
- [ ] Write tests `claimsCleanupAtMostOnceAcrossWorkers`, `doesNotClaimBeforeLeaseAndGraceExpire`, `refreshesLongUploadLease`, `doesNotDeleteWhenReferenceExists`, `doesNotDeleteWhenDatabaseCheckFails`, `retriesDeleteAfterAmbiguousSuccess`, and `backsOffAndPreservesOriginalFailure`.
- [ ] Run focused lifecycle tests and verify RED.
- [ ] Implement short transactions for intent creation, claim/lease updates, upload metadata persistence, attachment, cleanup claims, and completion. Use conditional updates/row locks so one worker owns a claim; ensure attach locks rows and requires READY, cleanup claims require no references.
- [ ] Implement stale recovery with safety age greater than provider timeout plus clock-skew margin. Reconciler only processes expired leases, extends/reclaims by token, and uses bounded retry/backoff. Database unavailability or ambiguous commit leaves the asset untouched and pending.
- [ ] Run lifecycle and concurrency tests to GREEN. If H2 cannot prove multi-worker semantics, include the contention case in PostgreSQL Testcontainers suite and mark it gated when Docker is unavailable.
- [ ] Commit only Task 4 files with `feat: add media asset recovery lifecycle`.

## Task 5: Designer Upload Orchestration and Transactional Persistence

**Files:** create orchestrator/persistence service and focused exception mapping; modify `Model3dController` and `Model3dService` only for designer flow; create service/controller/security tests.

**Interfaces:** consumes validator, storage, lifecycle services; persistence accepts only internal asset IDs created by the current operation. Existing AI endpoint remains wired to `CloudinaryService`.

- [ ] Write service tests `validatesAllFilesBeforeFirstUpload`, `compensatesModelAfterSecondImageUploadFails`, `compensatesNewAssetsAfterDatabaseSaveFailure`, `doesNotDeleteWhenCommitOutcomeIsAmbiguous`, `cleanupFailureKeepsOriginalUploadOrDatabaseError`, and `createsPendingModelWithFirstImagePrimary`.
- [ ] Write controller/security tests proving anonymous and wrong-role POST rejection, authenticated designer owner derived from `UserPrincipal`, client designer ID/path/public IDs ignored, and AI upload still invokes the legacy service.
- [ ] Run focused tests and verify RED.
- [ ] Implement non-transactional upload orchestration: validate full request first, create intents, upload assets one at a time, persist trusted response metadata after each success, persist model+images in one separate transaction, then return the existing `ModelResponse` path fields as public IDs.
- [ ] On provider/database failure, request cleanup only for current-operation IDs; use fresh primary reads before any delete, and preserve the original HTTP error if cleanup fails.
- [ ] Map malformed/missing files to 400, payload bounds to 413, unsupported/mismatched content to 415, and provider unavailability to 502/503 without stack traces or provider details.
- [ ] Run focused service/controller/security tests to GREEN and confirm model status remains PENDING.
- [ ] Commit only Task 5 files with `feat: orchestrate safe designer media uploads`.

## Task 6: URL Resolution, Product Catalog Regression, and Final Verification

**Files:** update `ProductServiceImpl`, add/use `MediaUrlResolver`, modify relevant repositories for batch metadata retrieval; add URL and catalog tests; update migration/reconciliation docs if implementation differs from approved spec.

**Interfaces:** consumes media associations and lifecycle records; preserves all current TASK 01 DTO fields and filters.

- [ ] Write tests `catalogUsesTrustedAssetUrlForNewMedia`, `catalogFallsBackToLegacyPublicId`, `catalogReturnsNullWhenMediaMissingOrInvalid`, `productListLoadsMediaInBatches`, and existing contract/security assertions for ACTIVE DESIGNER_MODEL only.
- [ ] Run focused product tests and verify RED.
- [ ] Implement URL resolution from persisted `secure_url` only after checking provider, configured cloud, resource type, media kind, and required format. Keep legacy path resolution for rows with no media reference; never synthesize an empty/fake URL.
- [ ] Ensure summary/detail load media metadata in batches, and AI media is not exposed through product catalog.
- [ ] Run focused media/catalog/security tests, then the full `mvn clean test`, then `git diff --check`. Capture exact test counts and note any PostgreSQL/Testcontainers skip or Cloudinary/PostgreSQL unavailable gate.
- [ ] Review complete Git status/diff against preflight; confirm no unrelated order/payment/pricing edits and no existing user changes were overwritten.
- [ ] Commit only Task 6 implementation/tests/docs with `test: verify product media catalog compatibility`.

## Execution Notes

- Execute in the current dirty workspace as explicitly requested; do not create a new worktree or clean/reset existing state.
- Before each task, compare its file list against current `git status`. Never stage a whole directory or use broad `git add -A`; commit only files created/changed by that task.
- The media code may be implemented and locally tested even while V1/production migration remains blocked, but default application configuration must not silently create or update production schema.
- Any design deviation must be recorded in the execution ledger as `Ruling: <decision> — <reason> — <cost if wrong>`.
- Final review must report V1 prerequisite, PostgreSQL verification, real Cloudinary verification, cleanup/reconciliation limits, hardcoded pre-existing credential risk, tests, and changed files.
