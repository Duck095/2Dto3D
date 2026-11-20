# V1 Fresh PostgreSQL Schema Review Draft

**Status:** Draft for focused independent review. Owner-approved stock-history retention is reflected below. This file is not approval to execute a migration.

## Provenance and source snapshot

The database owner confirmed that no legacy PostgreSQL database exists. The legacy-adoption/baseline branch is therefore not applicable to initial provisioning; this V1 design is for a fresh database and does not claim compatibility with a legacy export.

The design was prepared against worktree HEAD `634b2473bb9f508ca61271dd4e455f2020e5309a` plus the current uncommitted source state. The manifest covers the entity package, enum package, `pom.xml`, `application.properties`, V2 migration, approved product-media design, and implementation plan. Manifest SHA-256: `11e2630a55aed649a560f6c68cb363738c7bd46e4f4cabca4335ed0db51a8198` (36 files). The working tree is dirty; review this proposal against the current source snapshot before implementation.

## Version and scope contract

- V1 filename remains `V1__legacy_schema_baseline.sql`, preserving the approved versioning contract. It initializes the pre-media application schema on an empty database; it is not used with `flyway baseline`.
- V1 defines the 17 current pre-media tables and no `media_asset` table or `media_asset_id` columns.
- V2 remains `V2__media_asset.sql`; it creates `media_asset` and adds nullable media references to `model_3d` and `model_images`.
- Fresh initialization must explicitly migrate V1 then V2. API startup remains Flyway-disabled and Hibernate `ddl-auto=validate`.

## Mapping checklist

| Entity/table | Keys, columns and relationship checks |
|---|---|
| `User` / `users` | Integer identity; name, email, phone, password; role/status enums; created/updated timestamps. Email uniqueness is a proposed business constraint. |
| `Printer` / `printers` | Integer identity; name, technology, dimensions and status enum. |
| `Color` / `colors` | Integer identity; unique name and optional hex code. |
| `Material` / `materials` | Integer identity; name/type, price, stock quantity, unit, availability enum. |
| `Cart` / `cart` | Integer identity; unique required user reference and timestamps. |
| `AIGenerationSession` / `ai_generation_sessions` | Integer identity; required user, optional image/prompt/dimensions, timestamp. |
| `Model3d` / `model_3d` | Integer identity; source/status enums; optional user and generation session; legacy file path, dimensions, category, variant. No media reference in V1. |
| `ModelImage` / `model_images` | Integer identity; required model, required legacy image path, primary flag, upload timestamp. No media reference in V1. |
| `ModelPart` / `model_parts` | Integer identity; required model/name/key; optional color. |
| `MaterialColor` / `material_colors` | Composite primary key `(material_id, color_id)`; both required; quantity. |
| `MaterialStockLog` / `material_stock_logs` | Integer identity; required material and creator; change type/quantity, optional note, timestamp. V1 protects the material reference with `ON DELETE RESTRICT`. |
| `PriceEstimation` / `price_estimations` | Integer identity; required model/material/printer; optional cost/time values and estimate timestamp. |
| `Order` / `orders` | Integer identity; required user/printer, total, status, dates. |
| `OrderItem` / `order_items` | Integer identity; required order/model/estimate, quantity and price snapshot. |
| `CartItem` / `cart_items` | Integer identity; required cart/model/estimate, quantity and added timestamp. |
| `Payment` / `payments` | Integer identity; unique required order, amount, method/status and paid timestamp. |
| `Feedback` / `feedback` | Integer identity; required user and unique order item; optional rating, comment, image, visibility and timestamp. |

All `GenerationType.IDENTITY` IDs use PostgreSQL identity columns. Java `Double` properties use `DOUBLE PRECISION`; `LocalDateTime` properties use `TIMESTAMP WITHOUT TIME ZONE`. The SQL mirrors the current entity optionality and includes indexes for observed repository lookup, join, and ordering paths.

## Explicit design decisions and policy notes

### Material stock-log retention — owner decision implemented

The database owner confirmed: **preserve stock history and retire materials as `UNAVAILABLE`**. V1 defines `fk_material_stock_logs_material` with `ON DELETE RESTRICT`, so physical deletion cannot cascade away stock audit rows.

The application is now aligned with that policy. `MaterialStockLog.material` keeps its required many-to-one association without `@OnDelete(CASCADE)`. `MaterialServiceImpl.removeMaterial` persists `UNAVAILABLE` instead of deleting the material, and repeated retirement remains safe. Material colors, stock logs, and historical estimate references remain attached.

The existing `DELETE /api/materials/{id}/remove` route and empty HTTP 200 response are preserved; DELETE now means retire. `MaterialResponse` includes `isAvailable`, and the GET `/api/materials/` response continues to include unavailable materials with that status. No new endpoint or authorization behavior was introduced.

Focused H2 integration coverage uses the real service/controller with a small H2-backed repository adapter. It exercises the material row, stock log, color association, historical estimate, repeat DELETE, missing-ID exception, empty DELETE response, GET status, and H2 rejection of direct physical deletion. It does not validate JPA metadata or substitute for PostgreSQL. PostgreSQL must still verify that a direct physical DELETE is rejected by the V1 FK when stock-history rows exist.

### Email uniqueness

`uq_users_email` is case-sensitive under the proposed PostgreSQL constraint and matches the existing exact-match `UserRepository.findByEmail` lookup. It does not normalize case or prevent differently-cased variants. No case-insensitive behavior is introduced. Any future case-folded login/uniqueness behavior requires a coordinated authentication and data migration design.

### Lengths, enum checks, and financial types

- Unannotated JPA strings use `VARCHAR(255)` in this design, including `slug`, `description`, `prompt`, and enum columns without explicit `length`. Changing these limits or using `TEXT` is a future coordinated entity/schema/API decision.
- Enum `CHECK` constraints list the current Java enum names. Adding or renaming an enum value requires a migration that updates the check constraint before the new value is written.
- Financial fields currently map as Java `Double`; V1 uses `DOUBLE PRECISION` to match. Moving money values to fixed-precision `NUMERIC` would require coordinated entity, calculation, API, and data migration work and is a future improvement, not part of this draft.
- The proposed target major PostgreSQL version is not confirmed. The existing Testcontainers test uses PostgreSQL 16, but the deployment owner must confirm the target major and test against it before execution.

## Planned PostgreSQL verification (not run)

1. Confirm the deployment PostgreSQL major version and pin the isolated Testcontainers image digest to that major.
2. Provision an empty disposable database; run V1 then V2 explicitly through Flyway. Do not baseline the empty database.
3. Assert Flyway history contains versions 1 and 2 and `flyway validate` succeeds.
4. Start the complete application context with `spring.jpa.hibernate.ddl-auto=validate`; confirm all entity tables, columns, types and associations validate, and schema catalogs do not change during API startup.
5. Verify identity/sequence inserts for every identity entity and composite-key persistence for `MaterialColor`.
6. Verify all FK/unique/check constraints, delete rules, null V2 references, and preserved V1 legacy path columns.
7. Exercise concurrent cleanup claims and attachment-versus-cleanup/reference checks against the migrated schema, not a Hibernate-generated schema.
8. Save redacted Flyway output, history, Hibernate validation result, catalog assertions, concurrency results, and schema-before/after comparison. Rehearse restoring the disposable database to its pre-migration snapshot.

These tests must not be reported as passing until run against PostgreSQL. The existing synthetic migration and Hibernate `create-drop` tests are supplemental only.
