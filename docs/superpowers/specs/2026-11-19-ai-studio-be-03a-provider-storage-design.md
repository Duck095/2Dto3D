# AI Studio BE-03A Provider and Private Storage Design

**Status:** Draft for Chat BE review
**Task:** `AI-STUDIO-BE-03A-PROVIDER-STORAGE-INTEGRATION`
**Production release:** HOLD
**Paid provider calls:** Not authorized

## Goal

Connect the existing AI Studio design pipeline to GPT Image, Meshy Multi-Image-to-3D, and private artifact storage without changing the BE-02 History, Timeline, Approval, or pipeline HTTP contract. The implementation must remain safe under provider timeouts, worker restarts, duplicate delivery, invalid provider output, quota exhaustion, and cross-owner artifact requests.

## Current repository behavior

- `GptImageTransport` and `MeshyTransport` are interfaces. `DesignProviderConfiguration` currently supplies unavailable transports unless a test or other bean replaces them.
- `GptImageReferenceProviderAdapter` checks requested slots. `MeshyModelProviderAdapter` checks the four-reference domain contract.
- `DesignPipelineWorker` creates a submission marker before external calls, stores a Meshy task reference after submit, polls that reference on later claims, validates image/GLB outputs, and calls `DesignUsageReservationService`.
- A job with `PROVIDER_SUBMISSION_STARTED` and no provider task reference is held as `PROVIDER_OUTCOME_UNKNOWN`; a reservation with unknown usage cannot be released.
- `DesignArtifactStoreConfiguration` defaults to `UnconfiguredDesignArtifactStore`. `DesignArtifactController` checks project and owner before reading artifact bytes.
- Design job, model, artifact descriptor, owner/project and reservation records already exist. No schema migration is needed for this integration.

## Decisions

1. **Keep the existing pipeline and interfaces.** Implement concrete HTTP transports and a configured filesystem store behind current domain interfaces. Do not introduce a second job system, public artifact URLs, or new client-facing endpoints.
2. **Use direct HTTP clients (`RestClient`) with bounded connect/read timeouts.** This keeps serialization, response limits, redaction, and retry behavior explicit and supports HTTP-stub tests. Do not add provider SDK dependencies.
3. **GPT Image uses the Images API.** Use `/v1/images/edits` when the job contains input artifacts and `/v1/images/generations` when it does not. Make one non-retried request per requested slot, using the normalized prompt plus a deterministic slot instruction. Enable the provider only by explicit configuration with a configured API key and supported model. The model is configuration-driven; reject retired `gpt-image-1`.
4. **Meshy uses the Multi-Image-to-3D API.** Submit exactly four approved views as data URIs, request GLB output, persist the returned task ID in existing job/model fields, and poll that same task. Download only HTTPS results from the configured/verified Meshy asset host; never forward the API key to the asset host. No automatic submit retry is allowed.
5. **Treat quota units as internal limits, not money or provider credits.** Reserve the configured stage units before submission. Settle the full reservation on confirmed successful completion or definitive accepted-task failure. Release only when the provider was not invoked or a response is documented as a definite zero-cost rejection. Any ambiguous submit, timeout after dispatch, malformed/partial success, or unreported outcome becomes `UNKNOWN`; do not release it. Do not infer USD or Meshy credits from these units.
6. **Filesystem is explicit and private.** Keep the unconfigured store as the default. When `ai-studio.artifacts.backend=filesystem` and a root are supplied, write under owner/project directories with opaque UUID keys, same-directory temp files and atomic move. Scope read/delete operations by `(ownerId, projectId, artifactKey)`. Continue reading legacy UUID-key files from the existing owner-scoped layout so stored history remains usable. Reject traversal, symlink escape and unknown key forms.
7. **No sensitive logging.** Never log API keys, Authorization headers, prompts, input/output image bytes, data URIs, signed download URLs, or provider error bodies. Log only safe operation codes, internal job IDs, and provider request/task references when safe; provider request IDs must be length- and character-bounded.
8. **Preserve BE-02 behavior.** No changes to History, Timeline, Approval, response DTOs, or endpoint paths. Artifact URLs remain owner-authenticated controller responses with no-store headers.

## Provider request and result contracts

### GPT Image reference generation

- Consume `ReferenceImageRequest.normalizedPrompt`, input artifact bytes/MIME types, and exactly the requested `ReferenceViewSlot` values.
- Construct each slot prompt deterministically from the normalized prompt and a fixed, versioned view instruction. Do not include the job ID or idempotency key in the user-visible prompt.
- Use input artifacts as image references in the edit request. For text-only projects use the generation endpoint.
- Request PNG output. Require a successful HTTP response, a non-empty `data` list with one image, strict base64 decoding, configured byte-size bounds, and image bytes accepted by `DesignImageValidator` downstream. Return the slot and MIME type `image/png`.
- Missing `b64_json`, unsupported model, invalid JSON, oversized payload, HTTP error, or transport failure must never be logged with response body. Once an outbound POST may have been dispatched, classify outcome as unknown; do not retry.

### Meshy model generation

- Require four distinct approved slots with non-empty bytes and image MIME types accepted by Meshy.
- Submit a Multi-Image task with `image_urls` as bounded data URIs, configured model, and `target_formats: ["glb"]`. The domain normalized prompt/revision feedback is not a supported multi-image geometry prompt in the documented request; do not silently send it as an unsupported field. The current contract continues to pass it to the provider adapter for future supported options, but BE-03A does not invent Meshy prompt semantics.
- Require a successful response containing a non-empty task ID. Persist it before any poll. Poll only by that saved ID. Map PENDING/IN_PROGRESS to processing, SUCCEEDED to result retrieval, FAILED to a definite provider failure, and CANCELED/unknown values to an explicit UNKNOWN state, never success.
- Require a valid HTTPS GLB URL from the verified Meshy asset host and a bounded response body. Return `model/gltf-binary`; the worker's existing `MediaValidationService.validateGlb` remains the acceptance gate before storage.
- GET status/download errors are safe to repeat against the same task/result URL within bounded backoff. The worker keeps the job PROCESSING and sets a future lease before retrying the same task; it never invokes submit again. Submit POST timeouts/network errors after dispatch remain unknown and must not retry.

## Artifact storage behavior

- New key remains opaque (UUID string); the path is derived only from validated owner ID, project UUID and artifact UUID: `<root>/owners/<owner>/projects/<project>/<artifact>.artifact`.
- A legacy UUID key resolves only to `<root>/owners/<owner>/<artifact>.artifact`; it is read-only compatibility behavior. New writes always use the project directory.
- Create a temp file in the destination directory, write and force content, then use `ATOMIC_MOVE` to the final UUID path. If atomic move is unsupported, fail closed and clean the temp file. Do not overwrite an existing object.
- Validate required identity, MIME allowlist, non-empty bytes and configured size bound. Compute SHA-256 and byte size from stored bytes.
- Read/delete require the matching owner and project. Validate normalized paths remain under root and reject symlinked path components that could escape it.
- On DB transaction rollback or a multi-artifact job failure, delete only the newly written artifacts using their owner/project scope. Cleanup failure is safely logged and leaves no public access path.

## Configuration and activation

- Provider beans default to unavailable. Each provider requires its own explicit `enabled=true`, non-empty credential, configured model and endpoint restricted to HTTPS. Missing credentials/configuration prevents calls and never falls back to a live default.
- Filesystem storage requires explicit backend selection and non-empty absolute root. The default store stays unavailable.
- Bound HTTP connect, response, polling and maximum-body sizes through validated configuration. Do not log bound configuration values containing credentials.
- Keep the existing AI feature gate and quota reservation checks. Provider enablement does not implicitly enable the product feature.

## Failure and consistency rules

- No retry of OpenAI image POST or Meshy submit POST after dispatch may have started.
- A failure before outbound dispatch may release the reservation only when the code can prove no request was sent. Ambiguous errors hold the reservation as `UNKNOWN`. A definitive terminal provider task failure is settled against the full reserved internal units rather than releasing quota based on assumptions about provider billing.
- Meshy polling retries use the persisted task reference and cannot resubmit. Persist the reference immediately after submit; a crash between remote acceptance and local persistence is handled by the existing submission marker as unknown and held, not duplicated.
- Validate every image and GLB before writing final records. Write artifacts before the DB completion transaction; on transaction failure, compensate by deleting those new artifacts. Preserve previously persisted versions.
- Do not change migration V1–V19, API contracts, commerce/payment, Admin 3D, slicer, or production configuration.

## Verification and acceptance

All HTTP calls are verified with local HTTP stubs or fake providers. No real API credentials or paid provider calls are used.

- GPT Image: text generation and image edit request shapes; one requested slot per call; normalized/slot prompt mapping; valid PNG response; empty/malformed JSON, MIME/size/base64 failure, 4xx/5xx, timeout, network error; no automatic POST retry; logs omit secrets/prompts/bodies.
- Meshy: exact four data URIs, submit response/task ID, persisted task polling through pending/processing/success/failure, bounded GLB download, invalid/unsafe URL rejection, malformed/unknown status, GET retry without resubmit, submit timeout unknown without retry.
- Worker and quota: restart after marker/no task ref does not resubmit; restart with task ref polls same task; duplicate claims do not duplicate submission; UNKNOWN reservation cannot be released; definite pre-dispatch failure releases only when proven safe; concurrent reservations remain within owner/global limits on PostgreSQL 16.
- Storage/controller: atomic creation, digest/size/MIME, cleanup on rollback, traversal/symlink rejection, owner/project isolation, private HTTP access, not-found behavior, and legacy key reads.
- Existing BE-02 History, Timeline, Approval and pipeline HTTP/security tests remain green.
- Focused Maven tests, PostgreSQL 16 disposable tests, full `.\mvnw.cmd package`, and `git diff --check` pass.

## Explicit non-goals

- Live provider calls, production deployment, feature activation in any shared environment, new billing/payment flows, exact USD reconciliation, new public APIs, schema migrations, provider SDK dependencies, or changes to legacy AI generation.

## Provider documentation consulted

- OpenAI Image generation: <https://developers.openai.com/api/docs/guides/image-generation>
- OpenAI Image edits API: <https://developers.openai.com/api/reference/resources/images/methods/edit>
- Meshy Multi-Image-to-3D: <https://docs.meshy.ai/en/api/multi-image-to-3d>

The provider documents were consulted on 2026-11-19. Provider capabilities can change; implementation must use the verified endpoint, fields and model configuration at implementation time. In particular, GPT Image model names are configuration-driven and `gpt-image-1` is retired.
