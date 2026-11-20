# Media asset lifecycle and reconciliation

Tasks 4 and 5 add durable upload and cleanup transitions around the `media_asset` rows and wire the designer upload caller. The scheduled reconciler is only a poller. Database row locks and persisted lease tokens, not scheduler exclusivity or process identity, arbitrate ownership across instances.

## States and guards

| From | To | Required guard |
| --- | --- | --- |
| `PENDING_UPLOAD` | `UPLOADING` | Operation row lock plus conditional state/token/owner/expiry update. Only one claim returns an upload intent. |
| `UPLOADING` | `READY` | Matching operation, kind, and unexpired lease token; provider identity and metadata match the server intent. |
| `UPLOADING` | `UPLOADING` | Matching unexpired token may refresh the lease while the request is active. |
| `PENDING_UPLOAD`, `READY` | `CLEANUP_PENDING` | Live operation lease; conditional state/token/owner/expiry update; no model/image reference. The cleanup not-before time is the operation lease expiry plus grace. |
| `READY` | `ATTACHED` | Attachment transaction locks all operation rows and conditionally updates each row, confirming every asset belongs to the operation, its lease is current, and a model/image reference exists. |
| `CLEANUP_PENDING` | `CLEANUP_LEASED` | Lock and conditional state/due-time/retry update; no operation sibling is upload-active or attached; no references; exact generated Cloudinary identity; retry/backoff and safety age have elapsed. |
| `CLEANUP_LEASED` | `DELETED` | Matching cleanup token, provider delete success/not-found, and a final no-reference check. |
| `CLEANUP_LEASED` | `CLEANUP_PENDING` | Matching token on provider failure, or an expired cleanup lease is recovered. |

`ATTACHED` and `DELETED` are terminal for this task. Intent creation is atomic for the full asset set of one operation. Every row in that set receives the same server-owned operation token, owner, and lease expiry. Uploads can then be claimed one at a time, so a GLB and its images may upload sequentially while later files remain pending. The upload caller must retain the returned operation lease and refresh it while any upload is in progress; refresh renews all pending, uploading, and ready siblings together. It must attach the full set in the same transaction that writes model/image references, or request cleanup for the full set after a known failed operation. `requestCleanup` leaves an actively uploading row alone and delays pending siblings until the current operation lease expiry plus grace.

The reconciler groups expired rows by operation and locks every row for that operation. It recovers the set only after every pending, uploading, or ready row's shared lease has expired by the safety grace and no row is attached or referenced. It then marks the set cleanup-pending together. This also recovers an abandoned operation durably after process loss. An old operation token cannot renew or persist an upload after cleanup recovery because state/token/expiry checks no longer match. A cleanup claim uses a conditional database update as its final arbitration, in addition to operation checks and row locks; simultaneous workers cannot both own the same asset. Candidate selection filters due time and retry limit in the database before applying the bounded batch limit, so backoff-ineligible rows do not hide eligible work.

The designer caller contract is: finish request metadata, image, and GLB validation for all files before creating one batch of upload intents; retain the returned operation lease; for each file, claim its ID, upload with the server-issued intent, and persist the validated provider metadata before moving to the next file. `MediaUploadHeartbeat` refreshes the active intent immediately and schedules repeated lease refreshes during blocking provider I/O. It cancels and joins the in-flight refresh before returning, and a stale token or renewal failure prevents persistence from continuing. After all uploads succeed, the separate persistence transaction writes the model, image and part rows plus media references, then attaches the entire operation in that same transaction. On a known request failure, the caller requests cleanup for the entire operation; if cleanup cannot be requested, the original request error is preserved and the durable leases leave reconciliation to recover it. The endpoint derives the owner from the authenticated principal and ignores client-supplied owner IDs, file paths, and image paths. A process crash requires no in-memory callback: the durable leases let reconciliation recover it after expiry and grace.

The heartbeat uses the shared Spring task scheduler (`media-lifecycle-` threads), with pool size controlled by `media.lifecycle.heartbeat-pool-size` (default 200 to match the embedded Tomcat default request-thread ceiling). Configure it at least as high as the maximum simultaneous in-flight designer uploads for the deployment, and monitor delayed heartbeat execution. Lease durations still have to exceed the provider request timeout plus the configured margin. This process-local scheduler is not the ownership lock and cannot refresh after process loss.

## Transaction and provider boundaries

Intent creation, upload claim/renewal/result persistence, attachment, cleanup claim, fresh pre-delete verification, and cleanup completion each use short database transactions. No lifecycle transaction spans `MediaStorageService.delete`. Before deletion, the worker commits a cleanup lease, then performs a new read transaction that checks lease token, operation ID, expiry, generated public ID, provider/cloud identity, and references. A database error in either check prevents the provider call.

Cleanup uses the exact stored public ID, resource type, delivery type, and optional provider asset ID metadata. Missing `provider_asset_id` does not authorize deletion: the server-generated ID must still exactly match `product-media/{images|models}/{operation UUID}/{asset ID}` (with `.glb` for models). Cloudinary's adapter independently validates provider, cloud, namespace, and types.

## Lease and retry defaults

Defaults are 90 seconds for upload and cleanup leases, 90 seconds for the safety grace, 30 seconds for the first retry delay, one hour maximum delay, eight cleanup attempts, 100 rows per pass, and a 30-second polling interval. Both leases and the grace period must exceed the configured Cloudinary request timeout plus a 10-second safety margin; invalid settings fail startup. The upload caller must refresh its operation lease during a longer active request. An expired token cannot refresh or persist a late provider response.

Cleanup attempt count increments when a worker claims an attempt, so a process crash after claiming still consumes one bounded attempt. Provider failure returns the row to `CLEANUP_PENDING`; exponential backoff is capped. After the configured maximum attempts, the row remains auditable and pending but is no longer automatically claimed. Operator review is required to retry it.

## Recovery and limitations

- Pending intents are protected by their operation's shared lease, not by age alone. Reconciliation moves an abandoned operation to cleanup pending only after every active/pending/ready sibling lease has expired by the safety grace; it does not delete immediately.
- If Cloudinary accepted an upload but its response or metadata persistence was lost, the durable operation ID and generated public ID remain available for eventual cleanup. No provider deletion occurs while an upload lease or grace window is active.
- If a delete succeeds but marking `DELETED` has an ambiguous commit or database failure, the cleanup lease expires and a later attempt repeats the exact delete. Provider not-found is treated as idempotent success.
- If the database is unavailable before the call, cleanup stops. If it becomes unavailable after Cloudinary deletion, the row remains leased until expiry and the retry may see provider not-found.
- A process crash can delay work until the next poll and lease expiry. Provider or database outages can delay cleanup indefinitely. The reconciler provides bounded automatic attempts, not an absolute guarantee that every remote orphan is removed.
- PostgreSQL row locking and multi-instance contention require the Testcontainers test. H2 tests cover transitions and application logic but do not certify PostgreSQL concurrency semantics.

No production migration is executed by this task. V1 baseline adoption, V2 PostgreSQL verification, and actual provider verification remain release gates.

## Product catalog URL resolution

`GET /api/products` and `GET /api/products/{id}` preserve their Task 01 response fields and public filtering rules. New designer media URLs come from the attached `media_asset.secure_url` only after `MediaUrlResolver` checks the configured Cloudinary cloud, provider, media kind, attached state, image/raw resource type, `upload` delivery type, allowed format, operation/asset-derived public ID, provider version, and exact HTTPS Cloudinary host/path. If a row has a media association but fails validation, its URL is `null`; the service does not fall back to a path column. Rows without an association keep the legacy public-ID resolution behavior. `ModelResponse.filePath3d` and image `imagePath` remain public IDs.

Catalog page reads fetch thumbnail rows and their media metadata in one set-wise image query after the paginated model query. Detail reads fetch the model media association and all image media associations with entity graphs; model parts remain a separate bounded query. Repository predicates continue to require `DESIGNER_MODEL` and `ACTIVE`, excluding AI, pending, and inactive records.

The default suite verifies these mappings and query bounds against H2 and mocks Cloudinary. It does not verify PostgreSQL migration/locking behavior or real Cloudinary URL delivery. V1 baseline adoption, PostgreSQL V2 verification, and real provider upload/download/delete remain production release gates. Existing AI uploads continue to use the legacy service and are outside the public product catalog.
