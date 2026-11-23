-- Runtime reads are needed by the media lifecycle worker and product/catalog APIs.
GRANT USAGE ON SCHEMA public TO app_runtime;
GRANT SELECT ON TABLE
    public.users,
    public.staff_profiles,
    public.tasks,
    public.colors,
    public.model_3d,
    public.model_parts,
    public.model_images,
    public.media_asset,
    public.materials,
    public.material_colors
TO app_runtime;

-- V15 deliberately creates immutable mesh history under its dedicated owner.
SET ROLE admin3d_owner;
GRANT SELECT, INSERT ON TABLE
    public.product_glb_structure_snapshots,
    public.product_mesh_configuration_revisions
TO app_runtime;
RESET ROLE;

-- Upload workflows create products, images, media rows and immutable mesh history.
GRANT INSERT ON TABLE
    public.model_3d,
    public.model_images,
    public.media_asset
TO app_runtime;

-- Hibernate updates mutable entity columns; keep the identity and creation columns immutable.
GRANT UPDATE (
    media_kind,
    provider,
    cloud_name,
    provider_asset_id,
    public_id,
    resource_type,
    delivery_type,
    format,
    byte_count,
    provider_version,
    secure_url,
    state,
    lease_owner,
    lease_token,
    lease_until,
    retry_count,
    last_error_code,
    updated_at
) ON TABLE public.media_asset TO app_runtime;

-- Catalog mutations remain subject to the existing ADMIN HTTP authorization rules.
GRANT INSERT ON TABLE public.colors, public.materials, public.material_colors TO app_runtime;
GRANT UPDATE (name, type, price_per_gram, stock_quantity, unit, is_available)
    ON TABLE public.materials TO app_runtime;
GRANT DELETE ON TABLE public.material_colors TO app_runtime;

-- Only sequences used by media, product, color and material inserts are exposed.
-- The unrelated payOS sequences stay owned by the restricted trust role.
GRANT USAGE, SELECT ON SEQUENCE
    public.media_asset_id_seq,
    public.model_3d_model_id_seq,
    public.model_images_image_id_seq,
    public.colors_color_id_seq,
    public.materials_material_id_seq
TO app_runtime;

DO $$
BEGIN
    IF NOT pg_catalog.has_table_privilege('app_runtime', 'public.media_asset', 'SELECT')
            OR NOT pg_catalog.has_column_privilege('app_runtime', 'public.media_asset', 'state', 'UPDATE') THEN
        RAISE EXCEPTION 'app_runtime must be able to inspect and transition media_asset rows' USING ERRCODE = '55000';
    END IF;
    IF pg_catalog.has_table_privilege('app_runtime', 'public.media_asset', 'DELETE')
            OR pg_catalog.has_table_privilege('app_runtime', 'public.media_asset', 'UPDATE')
            OR pg_catalog.has_column_privilege('app_runtime', 'public.media_asset', 'id', 'UPDATE')
            OR pg_catalog.has_column_privilege('app_runtime', 'public.media_asset', 'operation_id', 'UPDATE')
            OR pg_catalog.has_column_privilege('app_runtime', 'public.media_asset', 'created_at', 'UPDATE')
            OR pg_catalog.has_table_privilege('app_runtime', 'public.model_3d', 'UPDATE')
            OR pg_catalog.has_schema_privilege('app_runtime', 'public', 'CREATE')
            OR pg_catalog.pg_has_role('app_runtime', 'admin3d_command_owner', 'MEMBER')
            OR pg_catalog.has_table_privilege('app_runtime', 'public.admin3d_publication_policy', 'SELECT') THEN
        RAISE EXCEPTION 'V20 would violate the restricted runtime privilege boundary' USING ERRCODE = '55000';
    END IF;
END;
$$;
