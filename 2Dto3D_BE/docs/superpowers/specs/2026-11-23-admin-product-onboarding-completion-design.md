# Admin Product Onboarding Completion Design

**Task:** `SYS-ADMIN3D-PRODUCT-ONBOARDING-COMPLETION-02`
**Status:** Design direction approved by SYSTEM; this document is submitted for specification review.
**Database baseline:** PostgreSQL 16, Flyway V1–V20.
**Release state:** Public production and business-data onboarding remain HOLD.

## Goal

Complete the four unfinished Admin 3D onboarding areas while preserving the accepted upload, mesh snapshot/history, security, and catalog behavior: reloadable approved GLB media, published mesh-color customization, private 3MF storage, and Admin color/material management.

## Existing behavior and constraints

- Admin product creation already validates and uploads GLB/images through the durable `media_asset` lifecycle and records a GLB structure snapshot. Admin can configure exact primitive occurrences and persist immutable configuration revisions in V15.
- Product publication already checks attached Cloudinary GLB/image identities. Public product detail is limited to active designer products and resolves model media through `MediaUrlResolver`; unsafe media resolves to `null`.
- Admin product reload currently relies on same-session local GLB provenance. The Admin page does not reload an approved GLB through the safe public product detail contract.
- `ProductDetailResponse` contains legacy named model parts but not V15 occurrence-based color configuration. The storefront viewer has color controls, but the product detail page disables them.
- `media_asset.media_kind` is constrained to `IMAGE` and `MODEL_3D` in V2. The upload lifecycle and Cloudinary adapter validate media kind, format, generated identity, and delivery type.
- Admin material UI is currently read-only. Backend material create/update and color create APIs exist; colors are returned through materials but there is no endpoint to list unassigned colors.
- ThreeMF upload, persistence, and product association are not implemented.
- The accepted Color/Material not-found HTTP 404 changes and their tests are present in the starting worktree and must be retained unchanged unless a directly necessary compatibility fix is reviewed.
- V17 restricted product mutation boundaries, V20 scoped grants, PayOS roles/objects, Flyway V1–V20, and the persistent database/volume are immutable in this task.

## Proposed architecture

### 1. Approved GLB reload

Keep the existing Admin local preview limited to the current create session. When a designer product is `ACTIVE`, Admin product detail may request the existing public product-detail API and load only its validated `model3dUrl`; pending or inactive products do not receive a durable preview URL. Storefront continues to consume public-approved product details only. Client code must not derive delivery URLs from `filePath3d`, `public_id`, or local object URLs.

The existing resolver remains the trust boundary: only an attached asset with the configured Cloudinary identity, expected GLB kind/format, server-generated path, version, HTTPS host, and exact URL path can be delivered. Admin and storefront reload tests cover refresh/new-page reload, a missing URL, and viewer load failure/retry. The product API does not return provider credentials or private identifiers.

### 2. Published mesh-color configuration

Extend the public product-detail contract with the latest configuration for the product's current attached GLB. The public DTO includes exact `(nodeIndex, meshIndex, primitiveIndex)` identity, the customizable flag, and only the persisted default/allowed color IDs and safe catalog display fields (color name and hex; material identity/name where available). Configuration is returned only for an active published product and only when the configuration references its current media asset and structure snapshot.

The viewer maps GLTFLoader parser associations to those numeric occurrence identities. It does not map by mesh name. It clones source material instances before applying a selected allowed color, and changes only customizable occurrences. Non-customizable occurrences and unrelated material/texture properties retain their original values. The customer picker is built solely from the Backend allowlist; arbitrary IDs or hex values are not accepted as choices. Customer color selection is presentation state and does not mutate the persisted Admin configuration.

Admin configuration continues to use the existing GLB snapshot and revision APIs. Colors available for a selected material come from its configured color associations. No synthetic mesh configuration or hardcoded mesh name is introduced.

### 3. Private 3MF media and V21

Add `MediaKind.PRINT_3MF` to the existing media lifecycle. Validate a bounded `.3mf` ZIP package before provider I/O: extension and accepted MIME, ZIP structure, required `[Content_Types].xml` and `3D/3dmodel.model` entries, no absolute/traversal or duplicate paths, no encrypted entries, bounded entry count, and bounded total expanded bytes. Reuse upload intent, heartbeat, provider identity verification, durable state transitions, compensation, and media metadata. Store raw files with Cloudinary `authenticated` delivery and a server-generated `product-media/print-files/... .3mf` identity. Do not implement or return a customer/public download URL.

Create an Admin-only upload route for a product's print assets and an Admin metadata-list route. Each successful upload is appended as a version; older attached files remain referenced and are not deleted by replacement. The response contains only safe metadata (asset ID, sanitized original filename, byte count, upload timestamp, and status), never `secure_url`, provider credentials, or storage path. Failed uploads follow existing cleanup compensation.

Draft Flyway **V21** with only these schema changes:

1. Replace `ck_media_asset_kind` so it admits `PRINT_3MF` while preserving `IMAGE` and `MODEL_3D`.
2. Add `product_print_assets` linking product to media asset with foreign keys, sanitized display filename, uploader ID, timestamp, and composite key `(product_id, media_asset_id)` so multiple retained versions are supported without a new sequence.
3. Create the relation under the existing `admin3d_owner` boundary. Explicitly revoke default/public access and grant `app_runtime` only `SELECT, INSERT` on this table; it receives no `UPDATE`, `DELETE`, `TRUNCATE`, role membership, or new sequence privilege. Existing V20 media-asset privileges are reused without expansion. The lifecycle reference check must include the new table so attached 3MF assets cannot be reconciled as orphans.
4. Add migration assertions for required roles, effective privileges, and forbidden mutation privileges. Preserve V17 and PayOS ownership/privileges exactly.

V21 is a candidate only. Validate/apply it only in disposable PostgreSQL 16 tests in this task. **Do not apply V21 to `morphia3d_prod_prep` or any persistent database** until SYSTEM separately approves the migration and runbook.

Cloudinary documents `authenticated` assets as inaccessible through ordinary unsigned delivery URLs and requiring a signed URL or authentication token. This implementation will not create or return such a URL for 3MF files, so the file remains unavailable to customers. See [Cloudinary access controls](https://cloudinary.com/documentation/control_access_to_media) and [upload delivery types](https://cloudinary.com/documentation/upload_parameters).

### 4. Admin color/material catalog

Extend the existing `/admin/materials` screen rather than creating a duplicate screen. Add color list/create, material list/create/update, and material-color association editing using the existing shared API client and existing Admin-protected mutation endpoints. Add `GET /api/colors/` for the complete catalog (including unassigned colors); it uses the existing `app_runtime` SELECT privilege on `colors`. Keep color creation Admin-only; color reads retain the current public catalog-read policy. Validate required names, hex format, numeric material fields, color IDs, duplicates, and empty catalogs. Keep the accepted unknown-color name/ID 404 behavior.

## API and persistence contracts

- Existing `POST /api/admin/products`, publication, mesh snapshot, mesh configuration, product detail, material create/update, and color create routes remain backward-compatible.
- Public product detail gains optional occurrence-based mesh appearance data. Existing clients may ignore the added fields.
- New Admin print-asset routes accept multipart 3MF only and return safe metadata only. They do not expose public/download URLs.
- `GET /api/colors/` returns a validated deterministic color list; no database privilege change is needed.
- Product creation, publication policy, payment processing, customer order/payment APIs, and business records are unchanged.

## Failure and security behavior

- Invalid, oversized, malformed, or unsafe 3MF archives are rejected before storage upload.
- Anonymous and non-Admin requests to print-asset upload or Admin catalog mutations are rejected by the existing Admin security boundary.
- Public product detail does not reveal inactive product media or 3MF metadata. Invalid/missing/unapproved GLB media yields no model URL and the UI shows a recoverable unavailable/load-error state.
- A color outside a mesh's persisted allowlist is not rendered as a selectable option. A malformed public configuration is ignored safely rather than matched by name.
- Provider upload or persistence failures use lifecycle compensation; ambiguous outcomes do not trigger destructive cleanup.
- No user-facing flow invokes payment or printer/slicing behavior.

## Verification design

1. Backend unit tests cover ThreeMF signature/container validation, required entries, traversal/duplicate/encrypted entries, size/entry limits, provider identity and authenticated delivery type, and lifecycle compensation.
2. PostgreSQL 16 integration tests apply V1–V21 in a disposable database with migration/runtime/owner roles. Verify append-only 3MF associations, V1–V20 checksums/behavior, SELECT/INSERT access, denied UPDATE/DELETE/TRUNCATE/sequence access, and unchanged V17/PayOS boundaries.
3. Backend API tests cover Admin/anonymous/non-Admin authorization, GLB visibility before/after publication, safe URL behavior on invalid/unavailable media, 3MF success and failure responses, color/material create/list/update, duplicate/not-found/validation cases, and public DTO allowlists.
4. Frontend tests cover Admin catalog loading/editing/empty/error/permission states, product media refresh, exact occurrence mapping, allowlisted color selection, and preservation of uncustomizable source materials.
5. Isolated browser E2E uses synthetic users/products, disposable PostgreSQL, and test-controlled storage. It covers login, color/material setup, product+GLB upload, mesh discovery and configuration, 3MF upload, reload/new page, storefront model load and color selection, preserved mesh materials, and denied access/invalid uploads. A test route may serve the fake provider's exact GLB object to the browser; this is not evidence of a real provider upload.
6. Provider acceptance is reported separately. If Cloudinary configuration is absent or cannot be verified without exposing credentials, mark real-provider delivery `NOT VERIFIED`; the controlled fake-storage tests must not be described as provider acceptance.
7. Run focused BE/FE tests and the relevant repository CI checks. Do not run the entire CI suite solely on the laptop if the existing required CI can validate the branch.

## Non-goals and release gates

- No persistent migration execution, reset, schema/privilege mutation, or volume replacement.
- No public-production release, real customer/business data, payment, slicing, printer integration, or 3MF rendering.
- No changes to Flyway V1–V20, V17 restrictions, V20 grants beyond using their existing privileges, or PayOS trust boundary.
- No off-D backup requirement for local preparation; the previously accepted local data-loss risk remains.
- Business-data onboarding remains HOLD until SYSTEM reviews the candidate, provider status, E2E evidence, and any V21 migration/runbook separately.
