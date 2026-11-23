-- Preserve each private 3MF file as an append-only version linked to its product
-- and authenticated uploader. Runtime can read/append this metadata only.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'admin3d_owner')
            OR NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'app_runtime')
            OR NOT pg_catalog.pg_has_role(current_user, 'admin3d_owner', 'MEMBER')
            OR NOT pg_catalog.has_table_privilege('admin3d_owner', 'public.model_3d', 'REFERENCES')
            OR NOT pg_catalog.has_table_privilege('admin3d_owner', 'public.media_asset', 'REFERENCES')
            OR NOT pg_catalog.has_table_privilege('admin3d_owner', 'public.users', 'REFERENCES') THEN
        RAISE EXCEPTION 'V21 requires the approved Admin 3D owner and migration-role contract' USING ERRCODE = '55000';
    END IF;
END;
$$;

ALTER TABLE public.media_asset
    DROP CONSTRAINT ck_media_asset_kind;

ALTER TABLE public.media_asset
    ADD CONSTRAINT ck_media_asset_kind
    CHECK (media_kind IN ('IMAGE', 'MODEL_3D', 'PRINT_3MF'));

SET ROLE admin3d_owner;

CREATE TABLE public.product_print_assets (
    product_id INTEGER NOT NULL,
    media_asset_id BIGINT NOT NULL,
    original_file_name VARCHAR(255) NOT NULL,
    uploaded_by INTEGER NOT NULL,
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_product_print_assets PRIMARY KEY (product_id, media_asset_id),
    CONSTRAINT fk_product_print_assets_product
        FOREIGN KEY (product_id) REFERENCES public.model_3d (model_id) ON DELETE RESTRICT,
    CONSTRAINT fk_product_print_assets_media
        FOREIGN KEY (media_asset_id) REFERENCES public.media_asset (id) ON DELETE RESTRICT,
    CONSTRAINT fk_product_print_assets_uploader
        FOREIGN KEY (uploaded_by) REFERENCES public.users (user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_product_print_assets_file_name
        CHECK (length(btrim(original_file_name)) BETWEEN 1 AND 255)
);

REVOKE ALL ON TABLE public.product_print_assets FROM PUBLIC;
GRANT SELECT, INSERT ON TABLE public.product_print_assets TO app_runtime;

RESET ROLE;

DO $$
BEGIN
    IF NOT pg_catalog.has_table_privilege('app_runtime', 'public.product_print_assets', 'SELECT')
            OR NOT pg_catalog.has_table_privilege('app_runtime', 'public.product_print_assets', 'INSERT')
            OR pg_catalog.has_table_privilege('app_runtime', 'public.product_print_assets', 'UPDATE')
            OR pg_catalog.has_table_privilege('app_runtime', 'public.product_print_assets', 'DELETE')
            OR pg_catalog.has_table_privilege('app_runtime', 'public.product_print_assets', 'TRUNCATE')
            OR EXISTS (
                SELECT 1
                  FROM pg_catalog.pg_class relation
                  CROSS JOIN LATERAL pg_catalog.aclexplode(COALESCE(
                      relation.relacl, pg_catalog.acldefault('r', relation.relowner))) privilege
                 WHERE relation.oid = 'public.product_print_assets'::pg_catalog.regclass
                   AND privilege.grantee = 0
                   AND privilege.privilege_type IN ('SELECT', 'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE')
            )
            OR pg_catalog.to_regclass('public.product_print_assets_id_seq') IS NOT NULL
            OR pg_catalog.pg_has_role('app_runtime', 'admin3d_owner', 'MEMBER')
            OR pg_catalog.pg_has_role('app_runtime', 'admin3d_command_owner', 'MEMBER') THEN
        RAISE EXCEPTION 'V21 would violate the product print-asset privilege boundary' USING ERRCODE = '55000';
    END IF;
END;
$$;
