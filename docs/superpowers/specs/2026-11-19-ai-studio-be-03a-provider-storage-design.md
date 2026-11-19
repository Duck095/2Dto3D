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
3. **GPT Image uses a documented, endpoint-specific HTTP contract.** For `/v1/images/edits`, send `multipart/form-data`: repeated `image[]` file parts with each input's original bytes and MIME type, plus `model`, slot-specific `prompt`, `n=1`, configured `size`/`quality`, and `output_format=png`. Do not encode these parts as Meshy data URIs. For `/v1/images/generations`, send JSON with `model`, slot-specific `prompt`, `n=1`, configured `size`/`quality`, and `output_format=png`. Use one non-retried request per requested slot. Parse `data[0].b64_json`; do not send deprecated `response_format`. Enable only by explicit configuration with API key and supported model; reject retired `gpt-image-1`.
4. **Meshy uses the Multi-Image-to-3D API.** Submit exactly four approved views as data URIs, request GLB output, persist the returned task ID in existing job/model fields, and poll that same task. Download only HTTPS results from the configured/verified Meshy asset host; never forward the API key to the asset host. No automatic submit retry is allowed.
5. **Treat quota units as internal limits, not money or provider credits.** Reserve the configured stage units before submission. Settle the full reservation on confirmed successful completion or definitive accepted-task failure. Release only when the provider was not invoked or a response is documented as a definite zero-cost rejection. Any ambiguous submit, timeout after dispatch, malformed/partial success, or unreported outcome becomes `UNKNOWN`; do not release it. Do not infer USD or Meshy credits from these units.
6. **Filesystem is explicit and private.** Keep the unconfigured store as the default. New writes use namespace keys `dsa2:<ownerId>:<projectUuid>:<artifactUuid>` and owner/project directories, same-directory temp files and atomic move. Scope read/delete by `(ownerId, projectId, artifactKey)` and require the namespace tuple to match both arguments. A legacy UUID key is read-only and may resolve under the legacy owner directory only after a DB-backed authorizer proves that exact key is linked to a persisted input, reference image, or model row with the same owner and project. There is no owner-only fallback. Reject traversal, symlink escape and unknown key forms; legacy artifacts cannot be deleted through the new store.
7. **No sensitive logging.** Never log API keys, Authorization headers, prompts, input/output image bytes, data URIs, signed download URLs, or provider error bodies. Log only safe operation codes, internal job IDs, and provider request/task references when safe; provider request IDs must be length- and character-bounded.
8. **Preserve BE-02 behavior.** No changes to History, Timeline, Approval, response DTOs, or endpoint paths. Artifact URLs remain owner-authenticated controller responses with no-store headers.

## Provider request and result contracts

### GPT Image reference generation

- Consume `ReferenceImageRequest.normalizedPrompt`, input artifact bytes/MIME types, and exactly the requested `ReferenceViewSlot` values.
- Construct each slot prompt deterministically from the normalized prompt and a fixed, versioned view instruction. Do not include the job ID or idempotency key in the user-visible prompt.
- For edits, use multipart `image[]` file parts containing raw artifact bytes and their declared supported MIME types; never convert OpenAI edit parts into data URIs. For text-only projects use a JSON generation body. Include `model`, slot prompt, `n=1`, configured `size` and `quality`, and `output_format=png` in both bodies.
- Require successful HTTP response, non-empty `data` with exactly one image, strict `b64_json` decoding, configured byte-size bounds and PNG signature. Do not send deprecated `response_format`. Return the slot and MIME type `image/png`; `DesignImageValidator` remains the worker's decoder gate.
- Missing `b64_json`, unsupported model, invalid JSON, oversized payload, HTTP error, or transport failure must never be logged with response body. Once an outbound POST may have been dispatched, classify outcome as unknown; do not retry.

### Meshy model generation

- Require four distinct approved slots with non-empty bytes and image MIME types accepted by Meshy.
- Submit a Multi-Image task with `image_urls` as bounded data URIs, configured model, and `target_formats: ["glb"]`. The domain normalized prompt/revision feedback is not a supported multi-image geometry prompt in the documented request; do not silently send it as an unsupported field. The current contract continues to pass it to the provider adapter for future supported options, but BE-03A does not invent Meshy prompt semantics.
- Require a successful response containing a non-empty task ID. Persist it before any poll. Poll only by that saved ID. Map PENDING/IN_PROGRESS to processing, SUCCEEDED to result retrieval, FAILED to a definite provider failure, and CANCELED/unknown values to an explicit UNKNOWN state, never success.
- Require a valid HTTPS GLB URL from the verified Meshy asset host and a bounded response body. Return `model/gltf-binary`; the worker's existing `MediaValidationService.validateGlb` remains the acceptance gate before storage.
- `MeshyModelProvider.TaskStatus` includes `UNKNOWN`. Unknown status and CANCELED are never success; hold the job and change the reservation to `UNKNOWN` without resubmit. Definitive FAILED ends the job and settles the reserved internal quota units conservatively.
- Transient status/result GET timeout or network failure raises a safe-read-retry outcome. The worker keeps the job PROCESSING, keeps its reservation RESERVED, and sets `nextAttemptAt`/`leaseUntil` using bounded exponential backoff (5 seconds base, 5 minutes maximum). Result retry polls the same task ID and obtains the latest signed GLB URL again; it does not rely on an expired URL and never submits again.
- Submit POST timeouts/network errors after dispatch remain unknown and must not retry. A saved task ID always resumes with GET only.

## Artifact storage behavior

- New key syntax is `dsa2:<ownerId>:<projectUuid>:<artifactUuid>`; validate all components strictly, require the encoded owner/project to equal the method arguments, and derive `<root>/owners/<owner>/projects/<project>/<artifact>.artifact`. Keys are internal and never returned by API responses.
- A legacy UUID key resolves to `<root>/owners/<owner>/<artifact>.artifact` only after `LegacyDesignArtifactProjectAuthorizer` confirms the key appears in a persisted artifact row for exactly `(ownerId, projectId)` in `DesignProjectInput`, `DesignReferenceImageVersion`, or `DesignModelVersion`. Controller/repository project checks remain mandatory; the store repeats the exact-link check. No owner-only fallback is permitted. Legacy reads are read-only; deletes reject legacy keys.
- Create a temp file in the destination directory, write and force content, then use `ATOMIC_MOVE` to the final UUID path. If atomic move is unsupported, fail closed and clean the temp file. Do not overwrite an existing object.
- Validate required identity, MIME allowlist, non-empty bytes and configured size bound. Compute SHA-256 and byte size from stored bytes.
- Read/delete require the matching owner and project. For legacy reads, require the exact DB relationship check above. Validate normalized paths remain under root and reject symlinked path components that could escape it.
- On DB transaction rollback or a multi-artifact job failure, delete only the newly written artifacts using their owner/project scope. Cleanup failure is safely logged and leaves no public access path.

## Configuration and activation

- Provider beans default to unavailable. Each provider requires its own explicit `enabled=true`, non-empty credential, configured model and endpoint restricted to HTTPS. Missing credentials/configuration prevents calls and never falls back to a live default.
- Filesystem storage requires explicit backend selection and non-empty absolute root. The default store stays unavailable.
- Bound HTTP connect, response, polling, read-retry and maximum-body sizes through validated configuration. Production provider URLs must remain HTTPS; there is no insecure HTTP override property. Do not log bound configuration values containing credentials.
- Keep the existing AI feature gate and quota reservation checks. Provider enablement does not implicitly enable the product feature.

## Failure and consistency rules

- No retry of OpenAI image POST or Meshy submit POST after dispatch may have started.
- A failure before outbound dispatch may release the reservation only when the code can prove no request was sent. Ambiguous errors hold the reservation as `UNKNOWN`. A definitive terminal provider task failure is settled against the full reserved internal units rather than releasing quota based on assumptions about provider billing.
- Meshy polling retries use the persisted task reference and cannot resubmit. Transient GET failures keep quota RESERVED during bounded retries; UNKNOWN/CANCELED statuses move quota to UNKNOWN; definitive FAILED and successful completion settle the full reservation. Persist the task reference immediately after submit; a crash between remote acceptance and local persistence is handled by the existing submission marker as unknown and held, not duplicated.
- Validate every image and GLB before writing final records. Write artifacts before the DB completion transaction; on transaction failure, compensate by deleting those new artifacts. Preserve previously persisted versions.
- Do not change migration V1–V19, API contracts, commerce/payment, Admin 3D, slicer, or production configuration.

## Verification and acceptance

All HTTP calls are verified with local HTTP stubs or fake providers. No real API credentials or paid provider calls are used.

- GPT Image: HTTPS JSON generation request and HTTPS multipart edit request; edit `image[]` parts contain raw bytes/MIME (no data URI); generation JSON contains model/prompt/n/size/quality/output_format; verify one slot per POST, prompt mapping, valid PNG response, base64/MIME/size failures, no retry, and secret redaction.
- Meshy: exact four JSON `image_urls` data URIs (distinct from GPT multipart); submit response/task ID, persisted task polling through PENDING/IN_PROGRESS/SUCCEEDED/FAILED/UNKNOWN/CANCELED, bounded GLB download, invalid/unsafe URL rejection, GET retry/backoff without resubmit, and submit timeout unknown without retry.
- Worker and quota: restart after marker/no task ref does not resubmit; saved ref resumes GET only; transient GET leaves PROCESSING plus future lease and RESERVED quota; UNKNOWN/CANCELED holds UNKNOWN quota; definitive FAILED settles reserved units; duplicate claims do not duplicate submit; proven pre-dispatch failure is the only automatic release; PostgreSQL 16 owner/global reservations remain within limits.
- Storage/controller: atomic creation, digest/size/MIME, cleanup on rollback, traversal/symlink rejection, namespaced key mismatch denial, legacy key allowed only with exact DB owner/project/link, same-owner cross-project denial, private HTTP access, and no legacy delete.
- Existing BE-02 History, Timeline, Approval and pipeline HTTP/security tests remain green.
- Focused Maven tests, PostgreSQL 16 disposable tests, full `.\mvnw.cmd package`, and `git diff --check` pass.

## Explicit non-goals

- Live provider calls, production deployment, feature activation in any shared environment, new billing/payment flows, exact USD reconciliation, new public APIs, schema migrations, provider SDK dependencies, or changes to legacy AI generation.

## Provider documentation consulted

- OpenAI Image generation: <https://developers.openai.com/api/docs/guides/image-generation>
- OpenAI Image generations API: <https://developers.openai.com/api/reference/resources/images/methods/generate>
- OpenAI Image edits API: <https://developers.openai.com/api/reference/resources/images/methods/edit>
- Meshy Multi-Image-to-3D: <https://docs.meshy.ai/en/api/multi-image-to-3d>

The provider documents were consulted on 2026-11-19. Provider capabilities can change; implementation must use the verified endpoint, fields and model configuration at implementation time. In particular, GPT Image model names are configuration-driven and `gpt-image-1` is retired.
