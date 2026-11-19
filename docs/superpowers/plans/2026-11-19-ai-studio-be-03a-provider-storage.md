# AI Studio BE-03A Provider and Private Storage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Connect the existing BE-02 design pipeline to configured GPT Image, Meshy Multi-Image-to-3D, and private filesystem artifact storage while preserving client contracts and fail-closed cost behavior.

**Architecture:** Keep `GptImageTransport`, `MeshyTransport`, `DesignArtifactStore`, adapters, worker and controllers as the pipeline boundaries. Add configured `RestClient` implementations and an owner/project-scoped atomic filesystem store; use existing job/task-reference and reservation records for idempotency and recovery without a schema migration.

**Tech Stack:** Java 17, Spring Boot `RestClient`, Spring configuration properties, existing JPA/Flyway schema, JUnit 5/Mockito, HTTP stubs, Testcontainers PostgreSQL 16.

**Spec:** `docs/superpowers/specs/2026-11-19-ai-studio-be-03a-provider-storage-design.md`

## Global Constraints

- Do not call a paid provider API; use local HTTP stubs and fake providers only.
- Keep `app.features.ai.enabled` and all provider activation disabled unless explicitly configured.
- Do not change BE-02 History, Timeline, Approval, or public HTTP response contracts.
- Do not edit V1–V19 or add migrations; existing job/task/reference/reservation fields are sufficient.
- Do not change commerce, payment, Admin 3D, slicer, legacy AI generation, shared environments, or production.
- Do not log API keys, prompts, artifact bytes, data URIs, signed result URLs, or raw provider error bodies.
- Do not retry OpenAI image POST or Meshy task-submit POST once dispatch may have begun.
- Preserve all pre-existing dirty files and worktree state.

## Review Focus

- A POST timeout after dispatch may have been billed: hold reservation `UNKNOWN`, never resubmit; tested in GPT transport and worker restart tasks.
- Meshy status/download timeout is safe only against the same persisted task: retry GET with bounded backoff and prove submit count stays one.
- Four GPT slot calls can partially complete: do not publish partial reference versions; hold the reservation unknown and leave no unreferenced artifacts after rollback.
- Owner ID alone is insufficient for new storage paths: require matching owner and project for reads/deletes while retaining legacy owner-scoped UUID reads.
- A Meshy response URL is untrusted: reject non-HTTPS or non-allowlisted hosts and do not forward provider authorization headers.

---

### Task 1: Provider and storage configuration gates

**Files:**
- Create: `src/main/java/com/exe/morphia3d/design/DesignProviderProperties.java`
- Create: `src/main/java/com/exe/morphia3d/design/DesignArtifactProperties.java`
- Create: `src/main/java/com/exe/morphia3d/design/DesignProviderCallException.java`
- Create: `src/main/java/com/exe/morphia3d/design/DesignProviderReadRetryException.java`
- Modify: `src/main/java/com/exe/morphia3d/design/DesignProviderConfiguration.java`
- Modify: `src/main/java/com/exe/morphia3d/design/DesignArtifactStoreConfiguration.java`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/exe/morphia3d/design/DesignProviderConfigurationTest.java`
- Test: `src/test/java/com/exe/morphia3d/design/DesignArtifactStoreConfigurationTest.java`

**Interfaces:**
- Produce `DesignProviderProperties` bound at `ai-studio.providers`, with GPT Image and Meshy `enabled`, `apiKey`, `baseUrl`, `model`, connect/read timeouts, and bounded response-size fields.
- Produce `DesignArtifactProperties` bound at `ai-studio.artifacts`, with `backend`, absolute `root`, and `maxBytes`.
- Defaults select unavailable providers and unconfigured storage. A live bean exists only when the matching provider has `enabled=true` and valid required configuration. Filesystem store exists only for `backend=filesystem` with an absolute root.

- [ ] Write tests proving default beans make no network calls and are unavailable; explicit enable with missing credentials/model or malformed HTTPS URL fails configuration; explicit filesystem selection without absolute root fails closed; the AI feature flag remains disabled by default.
- [ ] Run: `.\mvnw.cmd '-Dtest=DesignProviderConfigurationTest,DesignArtifactStoreConfigurationTest' test` — expected red for missing property/configuration behavior.
- [ ] Implement the property classes and conditional bean selection. Validate the supported GPT model config and reject retired `gpt-image-1`; require HTTPS provider base URLs and positive bounded timeout/body limits. Never log secret property values.
- [ ] Re-run the same command — expected all tests pass with defaults unavailable and configured test beans selectable.

### Task 2: GPT Image HTTP transport

**Files:**
- Create: `src/main/java/com/exe/morphia3d/design/HttpGptImageTransport.java`
- Reuse safe outcome type: `src/main/java/com/exe/morphia3d/design/DesignProviderCallException.java`
- Create: `src/test/java/com/exe/morphia3d/design/GptImageHttpTransportTest.java`
- Modify if needed: `src/main/java/com/exe/morphia3d/design/GptImageTransport.java`
- Reuse: `ReferenceImageProvider`, `ReferenceViewSlot`, `DesignImageValidator`, `DesignProviderProperties`

**Interfaces:**
- Implement `GptImageTransport.generate(ReferenceImageProvider.ReferenceImageRequest): List<ReferenceImageProvider.GeneratedReferenceImage>`.
- Use `/v1/images/edits` when `request.inputs()` is non-empty and `/v1/images/generations` otherwise. Issue one POST per requested slot, sequentially. Build prompt from `normalizedPrompt` plus a fixed versioned instruction for that slot. Output format is PNG.
- Add a safe provider transport exception carrying only a stable error code and a dispatch certainty (`NOT_DISPATCHED` or `OUTCOME_UNKNOWN`); never carry raw request/response content in its message.

- [ ] Write HTTP-stub tests for edit versus generation, model/prompt/output format, exact requested slot coverage and order, and input data URIs preserving declared MIME and bytes.
- [ ] Write tests for valid `b64_json`, malformed/empty JSON, missing image data, invalid base64, wrong PNG signature, oversized output, non-success HTTP response, timeout and network failure; assert request POST count is exactly one on uncertain failure and captured logs/errors contain no prompt, key, base64, or response body.
- [ ] Run: `.\mvnw.cmd '-Dtest=GptImageHttpTransportTest' test` — expected red for the absent concrete transport.
- [ ] Implement bounded `RestClient` calls. Strictly validate response shape, decode base64, check PNG signature and configured byte limit, and return only the requested slots. Do not retry. Classify local validation/configuration failures before dispatch as safe-not-sent and any failure after dispatch begins as unknown.
- [ ] Re-run the same command — expected all tests pass; worker-level decoder validation remains an additional check before storage.

### Task 3: Meshy asynchronous HTTP transport

**Files:**
- Create: `src/main/java/com/exe/morphia3d/design/HttpMeshyTransport.java`
- Reuse safe outcome type: `src/main/java/com/exe/morphia3d/design/DesignProviderCallException.java`
- Reuse safe GET retry type: `src/main/java/com/exe/morphia3d/design/DesignProviderReadRetryException.java`
- Create: `src/test/java/com/exe/morphia3d/design/MeshyHttpTransportTest.java`
- Modify if needed: `src/main/java/com/exe/morphia3d/design/MeshyTransport.java`
- Modify: `src/main/java/com/exe/morphia3d/design/MeshyModelProvider.java` (add `TaskStatus.UNKNOWN`)
- Reuse: `MeshyModelProvider`, `MeshyModelProviderAdapter`, `DesignProviderProperties`

**Interfaces:**
- Implement `MeshyTransport.submit(ModelRequest): String`, `status(String): TaskStatus`, and `result(String): GeneratedModel`.
- Submit to `/openapi/v1/multi-image-to-3d` with exactly four bounded `image_urls` data URIs, configured `ai_model`, and `target_formats: ["glb"]`. Require a valid non-empty task ID in the response.
- Poll `GET /openapi/v1/multi-image-to-3d/{taskId}`. Map PENDING/IN_PROGRESS/SUCCEEDED/FAILED explicitly; CANCELED/unknown map to `TaskStatus.UNKNOWN`, not success, and never cause submit.
- Download only `model_urls.glb` over HTTPS from the configured verified Meshy asset host, with no Authorization header, redirects disabled, and a strict maximum body size.

- [ ] Write HTTP-stub tests for four distinct approved image data URIs, submit parsing, pending/processing/success/failure transitions, result GLB retrieval, host/scheme rejection, missing task/GLB, oversized download, and malformed/unknown status.
- [ ] Write tests proving submit is never retried after timeout/network failure and GET poll/download can retry the same saved task/result without a second POST. Verify logs/errors do not expose the API key, image payloads, result URL query, or provider response body.
- [ ] Run: `.\mvnw.cmd '-Dtest=MeshyHttpTransportTest,DesignProviderAdapterTest' test` — expected red until the concrete HTTP transport exists.
- [ ] Implement bounded submit/poll/download calls with no POST retry, safe task-ID path encoding, and an explicit asset-host allowlist. Validate response metadata and leave full GLB parsing to the worker's existing `MediaValidationService.validateGlb` gate.
- [ ] Re-run the same command — expected all tests pass.

### Task 4: Private atomic filesystem artifact store

**Files:**
- Create: `src/main/java/com/exe/morphia3d/design/FilesystemDesignArtifactStore.java`
- Modify: `src/main/java/com/exe/morphia3d/design/DesignArtifactStore.java`
- Modify: `src/main/java/com/exe/morphia3d/design/DesignArtifactStoreConfiguration.java`
- Modify: `src/test/java/com/exe/morphia3d/design/DesignArtifactStoreTest.java`
- Modify test adapter: `src/test/java/com/exe/morphia3d/design/TEST_ONLY_FilesystemDesignArtifactStore.java`

**Interfaces:**
- Change scoped operations to `read(Integer ownerUserId, UUID projectId, String artifactKey): byte[]` and `delete(Integer ownerUserId, UUID projectId, String artifactKey): void`; keep `put(Integer ownerUserId, UUID projectId, String mimeType, byte[] content): DesignArtifactDescriptor`.
- New objects use `<root>/owners/<owner>/projects/<project>/<artifact>.artifact`; legacy UUID keys are read from `<root>/owners/<owner>/<artifact>.artifact` only. New keys are UUID strings and never contain paths.

- [ ] Add tests for owner/project read and delete isolation, opaque UUID keys, SHA-256/size/MIME metadata, legacy reads, traversal/invalid key denial, symlink escape denial, max-byte rejection, atomic create/no overwrite, and temp-file cleanup when write/move fails.
- [ ] Run: `.\mvnw.cmd '-Dtest=DesignArtifactStoreTest' test` — expected red for project-scoped API and production store.
- [ ] Implement validated path derivation under normalized absolute root; reject symlink components; write a same-directory temp, force bytes, and require `ATOMIC_MOVE`; delete temp on every failure. Support legacy read only. Keep the default bean unconfigured unless explicit filesystem properties select this implementation.
- [ ] Re-run the same command — expected all tests pass.

### Task 5: Wire project scope, worker outcomes, quota and rollback

**Files:**
- Modify: `src/main/java/com/exe/morphia3d/design/DesignPipelineWorker.java`
- Modify: `src/main/java/com/exe/morphia3d/design/DesignUsageReservationService.java`
- Modify: `src/main/java/com/exe/morphia3d/design/DesignProjectService.java`
- Modify: `src/main/java/com/exe/morphia3d/controller/DesignArtifactController.java`
- Modify: `src/test/java/com/exe/morphia3d/design/DesignProjectServiceTest.java`
- Modify: `src/test/java/com/exe/morphia3d/controller/DesignProjectHistoryHttpIntegrationTest.java`
- Modify: relevant design worker tests in `src/test/java/com/exe/morphia3d/repository/DesignPipelineReferenceWorkerPostgresTest.java` and `DesignPipelineModelWorkerPostgresTest.java`

**Interfaces:**
- All store reads/deletes pass the persisted `projectId` from the scoped project/job/input/reference/model record.
- Add `DesignUsageReservationService.settleReserved(UUID jobId, String providerUsageReference)` to settle the full reserved internal quota units on confirmed completion or definitive accepted-task failure without treating USD or provider credits as app units.
- Keep `processOne()` recovery invariant: submission marker and no task ID means UNKNOWN and no provider submit; a saved Meshy task ID means poll that same task only. Add a safe-read retry exception and reschedule GET failures by a bounded future lease while leaving the job PROCESSING.

- [ ] Add regression tests for reference generation partial success followed by failure cleaning all new objects; completion transaction rollback deleting only new artifacts; controller owner/project mismatch returns not found; OpenAI unknown POST and Meshy unknown submit keep reservation UNKNOWN; Meshy GET timeout leaves job PROCESSING with a future lease and retries the same task; restarted jobs never submit twice; confirmed success and definitive terminal provider failure settle the full reserved units; pre-dispatch failure releases only when proven safe; no uncertain reservation is released.
- [ ] Run: `.\mvnw.cmd '-Dtest=DesignProjectServiceTest,DesignProjectHistoryHttpIntegrationTest,DesignPipelineReferenceWorkerPostgresTest,DesignPipelineModelWorkerPostgresTest,DesignUsageReservationServiceTest' test` — expected red on unscoped artifact calls, cleanup and outcome handling.
- [ ] Wire owner/project parameters at every AI Studio store call. Ensure worker catches both checked IO and runtime failures during multi-artifact writes and compensates only artifacts created in that attempt. Keep provider POST errors unknown once dispatch can have started; retry only safe GETs against the persisted Meshy task reference by leaving the job PROCESSING and scheduling its next lease. Settle reserved units on confirmed success/definitive accepted-task failure, release only a proven pre-dispatch failure, and preserve UNKNOWN on ambiguous provider outcomes.
- [ ] Re-run the same command — expected all focused and PostgreSQL tests pass; HTTP History contract assertions remain unchanged.

### Task 6: PostgreSQL 16 concurrency, full regression and package

**Files:**
- Modify only if necessary: `src/test/java/com/exe/morphia3d/repository/DesignUsageReservationPostgresConcurrencyTest.java`
- Verify: all files from Tasks 1–5; no migration changes.

- [ ] Add/extend a PostgreSQL 16 Testcontainers case proving parallel owner/global reservations do not exceed limits and UNKNOWN reservations remain reserved after ambiguous provider completion.
- [ ] Run: `.\mvnw.cmd '-Dtest=DesignProviderConfigurationTest,DesignArtifactStoreConfigurationTest,GptImageHttpTransportTest,MeshyHttpTransportTest,DesignProviderAdapterTest,DesignArtifactStoreTest,DesignProjectServiceTest,DesignProjectHistoryHttpIntegrationTest,DesignPipelineReferenceWorkerPostgresTest,DesignPipelineModelWorkerPostgresTest,DesignUsageReservationServiceTest,DesignUsageReservationPostgresConcurrencyTest' test` — expected all named unit/HTTP/PG16 test classes pass; no external provider request.
- [ ] Run existing BE-02 focused History, Timeline, approval, pipeline HTTP/security suites and the complete `.\mvnw.cmd package` — expected zero test/build failures and no skipped required PostgreSQL checks.
- [ ] Run `git diff --check` and inspect the targeted whitespace scan; verify V1–V19, unrelated modules, and all pre-existing dirty files remain intact.
- [ ] Report test counts, PostgreSQL image/version, package exit status, changed paths, unknown-outcome policy, and any environment limitation. Do not claim production readiness or call a live provider.

## Plan self-review

- **Spec coverage:** Provider request/response validation, Meshy async lifecycle, crash/restart behavior, reservations, explicit config, artifact isolation/atomicity/legacy reads, BE-02 compatibility, HTTP/security tests, PostgreSQL 16, package and no-live-call rules map to Tasks 1–6.
- **Step scan:** Each task separates test-first, red verification, implementation and green verification. Interfaces/property names and exact test classes are pinned above.
- **Type consistency:** Store `read/delete` both take owner, `UUID projectId`, artifact key; `put` already takes owner/project. Usage settlement is `settleReserved(UUID, String)`. Meshy adds `TaskStatus.UNKNOWN`; safe transport exceptions contain only stable codes/outcome classification.
- **Review focus:** Each of the five risks listed above has an owning task and a named test assertion.
- **Scope:** No migration, API contract, commerce, payment, Admin 3D, slicer, shared environment or production changes.
