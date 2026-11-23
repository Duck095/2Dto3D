# Admin Product Onboarding Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task in the existing isolated worktree.

**Goal:** Complete approved GLB reload, published mesh-color selection, private 3MF storage, and Admin color/material management without changing persistent database state or release status.

**Architecture:** Reuse the existing public product media resolver, V15 mesh snapshot/revision model, and Admin material APIs. Add occurrence-based public appearance data for published products; add a narrowly scoped V21 association for authenticated 3MF assets and use existing media upload state transitions. Extend the existing Admin materials page and existing product/viewer components.

**Tech Stack:** Java 17, Spring Boot, PostgreSQL 16, Flyway, JPA, React, Three.js 0.170, Node.js test runner, Playwright, Testcontainers.

**Spec:** [`docs/superpowers/specs/2026-11-23-admin-product-onboarding-completion-design.md`](../specs/2026-11-23-admin-product-onboarding-completion-design.md)

## Global Constraints

- Keep Flyway V1–V20 byte-for-byte unchanged.
- Draft V21 and run it only in disposable PostgreSQL 16 tests; do not apply it to `morphia3d_prod_prep` or another persistent database.
- Preserve the V17 restricted product mutation and PayOS boundaries.
- Do not add public 3MF download or return provider credentials, storage paths, or authenticated URLs to clients.
- Serve GLB only through the existing validated public media resolver and only for active published products.
- Map mesh configuration by exact `(nodeIndex, meshIndex, primitiveIndex)` identity; never by hardcoded mesh names.
- Use only persisted Admin allowed colors for customer choices; leave non-customizable and unrelated source materials unchanged.
- Keep the accepted Color/Material not-found 404 changes and tests; commit those existing edits separately after verifying them in this worktree.
- Use synthetic data and disposable storage/database fixtures. Do not enter business data, run real payment, reset persistent storage, or release public production.
- If real provider readiness cannot be verified without disclosing secrets or contacting an external provider, report it as `NOT VERIFIED`; fake-storage E2E is not provider acceptance.

## Review Focus

1. **3MF ZIP traversal, encrypted/corrupt entries, or decompression bombs:** reject before any provider call; pinned in Task 4 validation tests.
2. **Authenticated 3MF URL leakage through DTOs, logs, or storefront:** never return a storage URL; pinned in Task 5 API tests and Task 7 browser/API assertions.
3. **Mesh configuration tied to an old GLB or stale snapshot:** return/use appearance only for the current media asset and snapshot; pinned in Task 3 Backend tests and Task 7 reload test.
4. **Color selection changing forbidden primitives or losing original PBR properties:** alter only allowlisted exact occurrences and preserve other source materials; pinned in Task 3 viewer tests and Task 7 browser test.
5. **V21 widening runtime/owner or PayOS privileges:** runtime may read/insert print-asset links only; pinned in Task 5 PostgreSQL privilege assertions and migration diff review.

---

### Task 1: Record and commit the accepted not-found remediation

**Files:**
- Commit unchanged from the approved baseline: `2Dto3D_BE/src/main/java/com/exe/morphia3d/service/impl/ColorServiceImpl.java`
- Commit unchanged from the approved baseline: `2Dto3D_BE/src/main/java/com/exe/morphia3d/controller/MaterialApiExceptionHandler.java`
- Commit unchanged from the approved baseline: `2Dto3D_BE/src/test/java/com/exe/morphia3d/controller/AdminProductCatalogIntegrationTest.java`

- [ ] **Step 1: Verify the three files match the accepted baseline diff** and V1–V20 have no changes.
- [ ] **Step 2: Run the two 404 regression methods** from `2Dto3D_BE` using `.\mvnw.cmd '-Duser.timezone=Asia/Ho_Chi_Minh' '-Dtest=AdminProductCatalogIntegrationTest#lookingUpUnknownColorReturnsNotFoundInsteadOfServerError+creatingMaterialWithUnknownColorIdReturnsNotFoundInsteadOfServerError' test -q`; expected: both pass.
- [ ] **Step 3: Commit only the three accepted files** as `fix: preserve color and material not-found responses`.

### Task 2: Add Admin color catalog and material management

**Files:**
- Modify: `2Dto3D_BE/src/main/java/com/exe/morphia3d/controller/ColorController.java`
- Modify: `2Dto3D_BE/src/main/java/com/exe/morphia3d/service/ColorService.java`
- Modify: `2Dto3D_BE/src/main/java/com/exe/morphia3d/service/impl/ColorServiceImpl.java`
- Modify: `2Dto3D_BE/src/main/java/com/exe/morphia3d/repository/ColorRepository.java`
- Modify: `2Dto3D_BE/src/test/java/com/exe/morphia3d/controller/AdminProductCatalogIntegrationTest.java`
- Modify: `2Dto3D_FE/src/services/api/endpoints.js`
- Add: `2Dto3D_FE/src/features/admin/materials/adminMaterialApi.js` and `adminMaterialApi.test.js`
- Add: `2Dto3D_FE/src/features/admin/materials/adminMaterialContract.js` and `adminMaterialContract.test.js`
- Modify: `2Dto3D_FE/src/pages/admin/AdminMaterialsPage.jsx` and its existing styles
- Add: `2Dto3D_FE/src/pages/admin/AdminMaterialsPage.test.js`

**Interfaces:**
- Add `ColorService.getAllColors()` returning a deterministic list of existing `ColorResponse` values.
- Add `GET /api/colors/`; retain current public read policy and Admin-only color/material mutations.
- Add FE API functions for `listAdminColors()`, `createAdminColor(values)`, `createAdminMaterial(values)`, and `updateAdminMaterial(id, values)` using the shared client and existing endpoints.
- Keep the `AdminMaterialsPage` route; it will display/create colors, display/create/update materials, and assign configured colors to materials.

- [ ] **Step 1: Write Backend RED tests** for `GET /api/colors/` ordering and inclusion of colors not assigned to materials; retain assertions that non-Admin cannot create colors/materials.
- [ ] **Step 2: Run the Backend RED test** from `2Dto3D_BE` with `.\mvnw.cmd '-Duser.timezone=Asia/Ho_Chi_Minh' '-Dtest=AdminProductCatalogIntegrationTest' test -q`; verify the new route/API behavior is absent.
- [ ] **Step 3: Implement the list endpoint** through `ColorController`, `ColorService`, `ColorServiceImpl`, and `ColorRepository`; return stable name/ID ordering and do not change grants.
- [ ] **Step 4: Write FE RED tests** for list/create/update requests, duplicate and validation messages, empty catalogs, and color association selection in `AdminMaterialsPage.test.js`.
- [ ] **Step 5: Install the locked FE dependencies** from `2Dto3D_FE` with `npm ci` in the worktree.
- [ ] **Step 6: Run FE RED tests** with `node --test src/features/admin/materials/adminMaterialApi.test.js src/features/admin/materials/adminMaterialContract.test.js src/pages/admin/AdminMaterialsPage.test.js`; verify the new catalog controls and API methods are missing.
- [ ] **Step 7: Implement the Admin catalog UI/API** using existing Backend routes for color create and material create/update. Keep malformed or duplicate values visible as recoverable form errors.
- [ ] **Step 8: Run Backend catalog tests and focused FE tests**, then commit as `feat: manage admin color and material catalog`.

### Task 3: Reload approved GLB in Admin

**Files:**
- Modify: `2Dto3D_FE/src/features/admin/products/adminProductApi.js`
- Modify: `2Dto3D_FE/src/features/admin/products/adminProductContract.js`
- Modify: `2Dto3D_FE/src/features/admin/products/adminProductApi.test.js`
- Modify: `2Dto3D_FE/src/features/admin/products/adminProductContract.test.js`
- Modify: `2Dto3D_FE/src/pages/admin/AdminProductsPage.jsx`
- Modify: `2Dto3D_BE/src/test/java/com/exe/morphia3d/controller/Admin3dRealE2ePostgresAcceptanceTest.java`

**Interfaces:**
- Add `getPublishedAdminProductMedia(id, { signal })` that calls the existing public product-detail endpoint and accepts only a valid safe `model3dUrl` for an active product.
- The Admin product view uses local GLB only while its session provenance is valid; after reload it requests published media and otherwise shows an unavailable/retry state.

- [ ] **Step 1: Write FE RED tests** for active product safe URL, pending/inactive product, malformed URL, refresh, and fetch/load failure retry; assert no fallback to `filePath3d`, `public_id`, or local object URL.
- [ ] **Step 2: Run focused FE tests** and verify reload currently cannot recover the GLB.
- [ ] **Step 3: Implement the public-detail adapter and Admin reload state**; do not add a new media-serving endpoint or expose pending media.
- [ ] **Step 4: Add isolated Backend/API tests** proving product detail returns a safe model URL only after publication and returns no model URL for pending, detached, invalid, or wrong-provider media.
- [ ] **Step 5: Run focused FE and Backend tests**, then commit as `feat: reload published GLB in admin`.

### Task 4: Publish mesh appearance and integrate the storefront viewer

**Files:**
- Modify: `2Dto3D_BE/src/main/java/com/exe/morphia3d/dto/response/ProductDetailResponse.java`
- Add: `2Dto3D_BE/src/main/java/com/exe/morphia3d/dto/response/ProductMeshAppearanceResponse.java`
- Modify: `2Dto3D_BE/src/main/java/com/exe/morphia3d/service/impl/ProductServiceImpl.java`
- Modify: `2Dto3D_BE/src/main/java/com/exe/morphia3d/repository/ProductMeshConfigurationRevisionRepository.java` and catalog/color/material repositories as required for the exact current configuration projection
- Test: `2Dto3D_BE/src/test/java/com/exe/morphia3d/service/impl/ProductServiceImplTest.java` and `ProductApiPostgresSmokeTest.java`
- Modify: `2Dto3D_FE/src/features/storefront/productApi.js` and `productApi.test.js`
- Modify: `2Dto3D_FE/src/features/storefront/productPresentation.js` and `productPresentation.test.js`
- Modify: `2Dto3D_FE/src/components/ThreeDViewer.jsx` and `ThreeDViewer.test.js`
- Modify: `2Dto3D_FE/src/pages/ProductDetailPage.jsx`

**Interfaces:**
- Add optional `modelMeshAppearances` to public product detail. Each row carries `nodeIndex`, `meshIndex`, `primitiveIndex`, `customizable`, `defaultColor { id, name, hexCode }`, and `allowedColors[]` with the same color shape; material ID/name is included for display where available.
- Backend selects only the latest valid revision for the product's current attached GLB and matching structure snapshot. Inactive products receive no appearance rows.
- Add a viewer prop `meshAppearances` consuming the exact numeric occurrence identity and color allowlist; selected customer colors remain presentation-only state.

- [ ] **Step 1: Write Backend RED tests** for current-asset/current-snapshot projection, exact allowed color fields, and no rows for unpublished/stale/unconfigured products.
- [ ] **Step 2: Run focused Backend tests** and verify the public DTO does not currently expose V15 occurrence configuration.
- [ ] **Step 3: Implement the safe public appearance projection** without changing V15 tables or Admin mesh endpoints.
- [ ] **Step 4: Write FE RED tests** mapping parser associations to exact occurrence triples; assert unmatched/non-customizable primitives preserve original material and that disallowed colors cannot be selected.
- [ ] **Step 5: Run focused FE tests** and verify the storefront currently has no occurrence configuration input.
- [ ] **Step 6: Implement product adaptation and viewer controls**. Clone affected material instances and change only their color; preserve untouched material slots and texture/PBR settings.
- [ ] **Step 7: Run product API, presentation, viewer, and Backend projection tests**, then commit as `feat: apply allowed mesh colors in storefront`.

### Task 5: Validate and store private 3MF media through the existing provider port

**Files:**
- Modify: `2Dto3D_BE/src/main/java/com/exe/morphia3d/enums/MediaKind.java`
- Modify: `2Dto3D_BE/src/main/java/com/exe/morphia3d/service/MediaUploadIntent.java`
- Modify: `2Dto3D_BE/src/main/java/com/exe/morphia3d/service/MediaValidationService.java`
- Modify: `2Dto3D_BE/src/main/java/com/exe/morphia3d/service/MediaUploadProperties.java` and configuration properties
- Modify: `2Dto3D_BE/src/main/java/com/exe/morphia3d/service/impl/CloudinaryMediaStorageService.java`
- Modify: `2Dto3D_BE/src/main/java/com/exe/morphia3d/service/MediaAssetLifecycleService.java`
- Test: `2Dto3D_BE/src/test/java/com/exe/morphia3d/service/MediaValidationServiceTest.java`
- Test: `2Dto3D_BE/src/test/java/com/exe/morphia3d/service/impl/CloudinaryMediaStorageServiceTest.java`
- Test: `2Dto3D_BE/src/test/java/com/exe/morphia3d/service/MediaAssetLifecycleServiceTest.java`

**Interfaces:**
- Add `MediaValidationService.validate3mf(MultipartFile)` returning `ValidatedMedia` with kind `PRINT_3MF` and normalized format `3mf`.
- Accept `.3mf` with MIME `model/3mf`, `application/vnd.ms-package.3dmanufacturing-3dmodel+xml`, or `application/octet-stream`; require ZIP container members `[Content_Types].xml` and `3D/3dmodel.model`.
- Set compressed upload max to 50 MiB, expanded-byte max to 250 MiB, and entry-count max to 4096; reject traversal, duplicate members, malformed/encrypted ZIP entries, and bound expansion while streaming.
- For `PRINT_3MF`, server identity uses raw resource, `authenticated` delivery, and `product-media/print-files/{operationId}/{assetId}.3mf`.

- [ ] **Step 1: Write RED validator tests** for valid synthetic 3MF, wrong extension/MIME/signature, missing required members, traversal/duplicate entry, corrupt/encrypted archive, compressed-size limit, expanded-size limit, and entry-count limit. Verify invalid files do not call storage.
- [ ] **Step 2: Run `MediaValidationServiceTest`** and confirm unsupported-kind behavior fails the new tests.
- [ ] **Step 3: Implement streaming validation and dedicated limits**; parse in memory-bounded fashion and do not extract archive paths to disk.
- [ ] **Step 4: Write RED provider tests** proving print assets use raw/authenticated delivery, `.3mf` server IDs, exact provider metadata validation, and reject insecure/mismatched URLs.
- [ ] **Step 5: Implement `PRINT_3MF` identity and Cloudinary authenticated upload/delete handling** without changing GLB/image identity or public URL resolution.
- [ ] **Step 6: Run validation, storage adapter, and lifecycle tests**, then commit as `feat: validate and store authenticated 3mf assets`.

### Task 6: Add V21 product print-asset association and Admin API

**Files:**
- Add: `2Dto3D_BE/src/main/resources/db/migration/V21__admin_product_print_assets.sql`
- Add: `2Dto3D_BE/src/main/java/com/exe/morphia3d/entity/ProductPrintAsset.java`
- Add: repository and request/response DTOs for product print assets
- Modify: `2Dto3D_BE/src/main/java/com/exe/morphia3d/service/MediaAssetLifecycleService.java`
- Add: `ProductPrintAssetUploadService`, transactional persistence service, `AdminProductPrintAssetController`, and scoped exception mapping
- Modify: `2Dto3D_BE/src/main/java/com/exe/morphia3d/config/SecurityConfig.java` only if the existing `/api/admin/products/**` matcher does not cover the new route
- Add: `2Dto3D_BE/src/test/java/com/exe/morphia3d/repository/ProductPrintAssetMigrationPostgresTest.java`
- Add: isolated Admin API integration tests

**Interfaces:**
- `POST /api/admin/products/{productId}/print-assets` accepts multipart `file`, requires ADMIN, validates/uploads one 3MF, appends a linked media-asset version, then attaches lifecycle state in the same persistence transaction.
- `GET /api/admin/products/{productId}/print-assets` returns filename, asset ID, byte count, status, and upload time only; it never returns provider URL or path.
- `product_print_assets` uses composite primary key `(product_id, media_asset_id)` and stores `original_file_name`, `uploaded_by`, and `uploaded_at`. It contains no sequence and retains replaced versions.
- V21 replaces only `ck_media_asset_kind` to add `PRINT_3MF`, creates the relation under `admin3d_owner`, revokes PUBLIC access, grants `app_runtime` only `SELECT, INSERT`, and asserts denied `UPDATE`, `DELETE`, `TRUNCATE`, and sequence privileges.
- `MediaAssetLifecycleService.hasReference(mediaAssetId)` recognizes the new link so attached print assets are not reclaimed as orphans.

- [ ] **Step 1: Write a disposable PostgreSQL RED migration test** that applies V1–V21 with migration/runtime/owner roles and asserts table shape, retained V1–V20 checksums, required SELECT/INSERT, denied UPDATE/DELETE/TRUNCATE on the new table, no new V21 sequence privileges, and unchanged owner-membership/PayOS boundaries.
- [ ] **Step 2: Run the migration test** and confirm V21 is missing; verify the test uses only its Testcontainers database.
- [ ] **Step 3: Implement V21 and entity/repository**; preserve V17 function owners and PayOS roles/objects exactly.
- [ ] **Step 4: Write RED service/API tests** for product ownership/type, current Admin identity, append-only versions, lifecycle reference, safe metadata-only responses, upload compensation, anonymous/user denial, and missing product behavior.
- [ ] **Step 5: Implement the upload/persistence/controller path** by reusing intent, heartbeat, `MediaStorageService`, and cleanup semantics; never expose signed/authenticated URLs.
- [ ] **Step 6: Run PostgreSQL 16 migration, lifecycle, and API tests**; assert Flyway validate V1–V21 passes and V1–V20 files are unchanged. Do not connect the test or migration command to persistent DB.
- [ ] **Step 7: Commit as `feat: persist admin product 3mf assets with v21`** and record that persistent execution remains NOT AUTHORIZED.

### Task 7: Add Admin 3MF upload and history controls

**Files:**
- Modify: `2Dto3D_FE/src/services/api/endpoints.js`
- Modify: `2Dto3D_FE/src/features/admin/products/adminProductApi.js` and API tests
- Modify: `2Dto3D_FE/src/features/admin/products/adminProductContract.js` and contract tests
- Modify: `2Dto3D_FE/src/pages/admin/AdminProductsPage.jsx` and related styles
- Add: focused tests for 3MF validation, upload pending/success/error states, and stored-version listing.

**Interfaces:**
- Add `uploadAdminProductPrintAsset(productId, file)` and `listAdminProductPrintAssets(productId, { signal })`.
- Product editor accepts `.3mf` only, enforces the Backend 50 MiB limit, labels provider upload status, and renders returned metadata without a download link.

- [ ] **Step 1: Write FE RED tests** for valid/invalid extension and size, pending/success/error states, history metadata, and absence of URL/path rendering.
- [ ] **Step 2: Run focused FE tests** and verify print-asset API/UI behavior is absent.
- [ ] **Step 3: Implement the Admin upload/history UI** against Task 5 contracts; keep it unavailable to non-Admin routes.
- [ ] **Step 4: Run focused Admin API/contract/UI tests**, then commit as `feat: add admin 3mf upload controls`.

### Task 8: Full isolated onboarding E2E and release-candidate verification

**Files:**
- Extend: `2Dto3D_BE/src/test/java/com/exe/morphia3d/controller/Admin3dRealE2ePostgresAcceptanceTest.java`
- Extend: `2Dto3D_FE/playwright-e2e/admin3d-real-e2e.e2e.js` with `real Admin completes product onboarding with materials, private 3MF and public viewer color selection`
- Add/update fixtures only under their test-owned fixture directories.

**Interfaces:**
- The E2E uses the existing disposable PostgreSQL 16 Testcontainer and a test-owned fake storage implementation. The browser route serving the fake GLB responds only for the exact test asset URL.
- E2E records status and hashes, not passwords/tokens; all fixture users/data/storage objects are synthetic.

- [ ] **Step 1: Add browser acceptance assertions** for Admin login, color/material create/association, product+GLB upload, inspector occurrences, allowed/non-customizable mesh configuration, 3MF upload/list, publish, logout/new page/login, Admin reload, public storefront GLB load, allowlisted color change, untouched material preservation, invalid 3MF and non-Admin denial.
- [ ] **Step 2: Run the new E2E method** with `-Duser.timezone=Asia/Ho_Chi_Minh` and its existing Testcontainers/PostgreSQL setup; expected: all journey assertions pass and no live provider calls occur.
- [ ] **Step 3: Run full relevant checks:** Backend `.\mvnw.cmd test -q`, FE `npm test`, FE `npm run build`, the new Admin browser E2E, and `git diff --check`. Inspect output for any migration changes outside V21 and for changes to V17/PayOS boundaries.
- [ ] **Step 4: Verify persistent DB was not targeted** by this work; report provider status separately as verified/unverified without printing secret values.
- [ ] **Step 5: Commit test/source changes as `test: accept admin 3d product onboarding flow`**; retain generated evidence under ignored test output, prepare normal protected integration/CI handoff, and do not apply V21 persistently or change public-production/business-data HOLD.

## Execution and acceptance gates

- Execute tasks in order in branch `codex/sys-admin3d-product-onboarding-completion-02` and the isolated worktree only.
- Run each task's failing test before its implementation, then the focused green tests.
- Before submitting the candidate, run the requested relevant CI/test checks and inspect all commits/files, including that accepted fixes are present and V1–V20 are unchanged.
- Report `GLB provider real-upload: NOT VERIFIED` unless a separately authorized real-provider acceptance is performed without secret disclosure.
- Candidate V21, its privilege evidence, and any runbook are for SYSTEM review only. Persistent migration, business-data onboarding, and public release require new SYSTEM acceptance.
