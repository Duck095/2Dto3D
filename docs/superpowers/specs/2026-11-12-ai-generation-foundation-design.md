# AI Generation Foundation Design

**Task:** SYS-P4-AI-GENERATION-FOUNDATION-01
**Status:** Design approved in conversation; implementation not yet approved
**Release:** HOLD

## Goal and constraints

Build a real, owner-scoped AI generation job foundation across the existing Spring and React applications. The foundation must never report a model as generated unless a provider returned a valid GLB and the result is retrievable through an owner-checked API.

The implementation must preserve the existing AI Studio workspace and viewer, existing `ai_generation_sessions` rows and model relationships, authentication/authorization, and current media safety behavior. It must not configure a paid provider, enable Cloudinary, write to an application database during development, change QA/production runtime, create a release JAR, or promote test output to a real model. Release remains HOLD.

## Current repository behavior

- `AiGenerationController` currently exposes only `POST /api/ai-studio/generations`, returning `503 AI_PROVIDER_NOT_CONFIGURED` with `accepted: false`.
- `ai_generation_sessions` already stores owner and prompt and is referenced by `model_3d.session_id`. Its semantics and existing data must remain intact.
- `/api/ai-models/{id}` checks that the authenticated owner matches the model owner. The existing `MediaValidationService` validates GLB signature, version, declared length, and configured byte size.
- The FE has a workspace and viewer, but the page currently shows a fixed unavailable notice and calls the create API only from the guarded interaction path. The declared service also has history and refresh paths which are not implemented on the backend. The demo AI service can fabricate a model URL and must not be selected for the real generation route.
- Both repositories contain extensive pre-existing uncommitted changes. Implementation must avoid reverting, staging, or rewriting those changes.

## Chosen architecture

Add a separate durable `ai_generation_jobs` aggregate rather than reinterpret legacy sessions. A job has a UUID identifier, authenticated owner foreign key, immutable input/options snapshot, status, safe error code/message, retry lineage, provider reference, idempotency key, attempt count, and created/updated/finished timestamps. Add a nullable relationship from a completed job to its owned output model or artifact. Use an additive Flyway migration after V8; do not edit applied migrations or backfill legacy sessions into invented statuses.

Statuses are `PENDING`, `PROCESSING`, `COMPLETED`, and `FAILED`. Transitions are monotonic: `PENDING -> PROCESSING -> COMPLETED|FAILED`; an explicit retry creates a new job linked to the failed job so the old result and error remain auditable. Provider and storage exception details are sanitized before exposure.

Provider execution uses a replaceable `AiGenerationProvider` port. The production/default bean is unavailable unless an approved implementation and credentials are explicitly configured. A separate artifact-store port persists validated output and serves it by an opaque job/model reference. No production Cloudinary bean is activated by this feature. The test profile may register only a deterministic fake provider and isolated temporary artifact store, both named and asserted as `TEST_ONLY`.

Use a database-backed worker with bounded polling and row claiming so accepted jobs survive application restarts and concurrent workers cannot process a job twice. Provider submission and retry must carry the stable job/idempotency key. Bound request bytes before buffering, enforce image media validation, use configured per-owner request limits, provider timeouts, and a small configured retry budget for transient failures only. Do not retry a submission whose outcome is unknown unless the provider supports idempotency. If the provider or required artifact store is unavailable at request acceptance time, return a structured unavailable response and do not persist an accepted job.

## API contract

All endpoints require an authenticated customer or administrator principal and derive owner identity only from the security principal. Every job lookup, retry, history query, and result access is constrained by owner in the repository query; an inaccessible job returns the same not-found behavior as an unknown ID.

- `POST /api/ai-studio/generations`: multipart request containing validated request data and at most one bounded input image; accepts an `Idempotency-Key`. Returns `202` with `{id, status, createdAt}` only after a configured provider and artifact store can accept the work. Missing provider configuration returns `503` with a stable code such as `AI_PROVIDER_NOT_CONFIGURED`, `accepted: false`, and no job row.
- `GET /api/ai-studio/generations`: paginated history for the current owner, newest first.
- `GET /api/ai-studio/generations/{id}`: current state and safe failure information; includes result metadata only when complete.
- `POST /api/ai-studio/generations/{id}/refresh`: owner-scoped retry of a failed job, producing a new job ID and preserving retry lineage.
- `GET /api/ai-studio/generations/{id}/result`: streams the validated GLB only for the owning user and a completed job. It does not expose provider URLs or internal storage identifiers.

The FE must treat `202` as pending, poll status with a bounded interval/backoff until terminal status, refresh owner history, and show structured unavailable/validation/provider errors. It must never call the viewer with a pending, failed, malformed, or unavailable result. Download/view access must use the owner-checked result endpoint. Existing viewer customization and print-draft behavior remain intact.

## Output validation and persistence

The worker validates provider output using the existing GLB validation rules before marking a job complete. Invalid, oversized, mismatched, or unavailable output fails the job with a safe code and cannot produce a model URL. The validated bytes are saved through the artifact-store port. A completed result is associated with the authenticated owner and, where the existing model persistence contract supports it, linked to an AI model without weakening the existing owner checks. Any required changes to model persistence must preserve legacy `AIGenerationSession` semantics and be covered by persistence tests.

No real Cloudinary upload occurs in this foundation's tests. The `TEST_ONLY` artifact store uses an isolated temporary directory and must not be enabled by dev/QA/production defaults. Tests must assert that non-test profiles cannot select it.

## Security, reliability, and limits

- Require authentication and the existing AI feature gate; preserve JWT and role authorization.
- Enforce owner scoping in data access, not only in controller logic.
- Validate prompt, quality, style, input mode, image MIME/signature/dimensions, and total request size before job acceptance.
- Configure limits for concurrent/rolling requests per owner, provider timeout, polling interval, and retry count. Do not hardcode a vendor-specific rate or timeout in business logic.
- Scope idempotency keys to the authenticated owner. Repeated accepted requests with the same key return the original job; conflicting payload reuse returns a structured conflict. A provider timeout with unknown submission outcome must not silently create a second paid request.
- Return generic not-found for other owners' job IDs and do not reveal whether such jobs exist.
- Keep credentials out of logs and error payloads. Never return raw provider responses or remote URLs.

## Testing and acceptance evidence

Backend unit tests cover state transitions, idempotency, per-owner limits, retry budget, transient/permanent provider failure, timeout/unknown submission, input validation, GLB validation, artifact persistence, and ownership. HTTP/security tests cover create, history, status, retry, and result access for owner, other owner, anonymous, and feature-disabled cases. A test-profile fake provider and temporary artifact store are explicitly marked `TEST_ONLY`; they may complete only test jobs with a valid fixture GLB.

FE tests cover the HTTP contract, `202` polling and terminal states, unavailable/error handling, history ownership reset, and rejection of incomplete results. Run FE unit tests, cross-repo contract checks, and build. Run focused backend tests followed by the full Maven test suite when the isolated test environment supports it. Run browser desktop/mobile flows against an isolated test runtime if the required local DB/browser runtime is available; otherwise report the precise blocker without claiming integration PASS.

No test may connect to QA/production or write to an application database. No paid provider call, real payment, Cloudinary activation, deployment, or release packaging is part of acceptance.

## Delivery boundaries and release gate

This foundation supplies contracts and safe execution boundaries, not an actual commercial 3D provider integration. Real generation remains unavailable until an approved provider implementation, credentials, timeout/retry contract, and provider-specific terms are supplied. A real persistent output store also needs explicit approval/configuration. Founder pricing/commercial approval is separate. The release gate remains HOLD until those approvals and provider-backed integration evidence exist.

## Open implementation details for the plan

The implementation plan must verify the existing Spring version's scheduler/transaction capabilities, PostgreSQL locking syntax, current model persistence/storage contracts, actual test fixtures, test-profile property isolation, FE polling lifecycle behavior, and browser test availability before editing. If any assumption conflicts with source evidence, stop and revise this design before implementation.
