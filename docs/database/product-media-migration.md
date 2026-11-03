# Product media schema migration

## Current boundary

The application has historically relied on Hibernate `ddl-auto=update` and has no committed Flyway history or reviewed V1 schema baseline. TASK 02 adds only the additive `V2__media_asset.sql` migration. The complete V1 legacy-schema baseline is a separate prerequisite and is intentionally not fabricated from H2 or JPA annotations.

API startup now uses `spring.jpa.hibernate.ddl-auto=validate` and `spring.flyway.enabled=false`. Hibernate validates the complete entity schema and fails startup if the V1/V2 schema is not already present. It will not create or update production tables. Flyway is invoked only by an explicit migration job; automatic baselining and Flyway clean are disabled.

## Fresh PostgreSQL database

1. Complete the separate V1 task and review its SQL against the canonical legacy schema.
2. Provision the database using an explicit Flyway migration run that applies V1 and V2 before starting the API.
3. Verify `flyway_schema_history` records both versions, then start the API with Hibernate validation enabled.

The media migration alone cannot initialize a fresh database because its foreign keys require the legacy `model_3d` and `model_images` tables. Until V1 exists and is verified, fresh PostgreSQL initialization is blocked.

## Existing Hibernate-managed database adoption

1. Take and verify a restorable database backup.
2. Compare the existing schema with the reviewed V1 baseline on a non-production copy. Resolve any drift before adoption.
3. Explicitly baseline the matching existing database at version 1 using the Flyway migration job. `baseline-on-migrate` remains false; application startup never baselines it.
4. In a separate approved deployment step, run `flyway migrate` to apply V2. Verify nullable references, indexes, provider identity constraints, and foreign keys on the staging copy.
5. Start the API only after migration completes; `ddl-auto=validate` will fail closed if required tables or columns are missing.

Supply Flyway database credentials through the deployment runner's protected Maven `settings.xml` server entry with ID `flyway-db` (or a protected Flyway config file populated at runtime from the secret manager). Do not pass `flyway.password` or database credentials in `-D` arguments, embed them in the JDBC URL, commit them, or print them in logs. The URL below contains no credentials.

```sh
mvn flyway:baseline -Dflyway.url=jdbc:postgresql://<DB_HOST>/<DB_NAME> -Dflyway.baselineVersion=1
mvn flyway:migrate -Dflyway.url=jdbc:postgresql://<DB_HOST>/<DB_NAME>
```

Do not run these commands against production from a developer workspace. Production adoption and migration require the normal database backup, review, and release approval process. If V1 has not been delivered, do not baseline or migrate; the release remains blocked.

## V2 invariants

- Existing `file_path_3d` and `image_path` values are untouched. New nullable references permit legacy rows to remain unlinked.
- `operation_id` is indexed but deliberately non-unique so one product creation can own multiple assets.
- Provider public identity and non-null provider asset identity are unique within the Cloudinary cloud/resource/delivery namespace.
- A model or image may reference at most one media asset; foreign keys use `ON DELETE RESTRICT`.
- `media_asset` has indexes for operation lookup and lifecycle reconciliation.
