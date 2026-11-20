package com.exe.morphia3d.service.admin3d.mesh;

import com.exe.morphia3d.dto.admin3d.mesh.MeshConfigurationRequest;
import com.exe.morphia3d.dto.admin3d.mesh.MeshPrimitiveIdentity;
import com.exe.morphia3d.service.glb.GlbStructure;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MeshConfigurationValidatorTest {
    private static final Integer PRODUCT_ID = 42;
    private static final Long MEDIA_ASSET_ID = 901L;
    private static final UUID SNAPSHOT_ID = UUID.fromString("84bc80c3-7d63-4a65-a647-35737cf19fc8");

    private final MeshConfigurationValidator validator = new MeshConfigurationValidator();
    private final GlbStructure structure = new GlbStructure(0,
            List.of(new GlbStructure.Scene(0, "Main", List.of(0))),
            List.of(new GlbStructure.Node(0, "duplicate", 1, List.of(), List.of(0)),
                    new GlbStructure.Node(1, "duplicate", 1, List.of(), List.of(0))),
            List.of(new GlbStructure.Mesh(0, "duplicate", List.of(
                            new GlbStructure.Primitive(0, 0, List.of("POSITION"), List.of()),
                            new GlbStructure.Primitive(1, 1, List.of("POSITION"), List.of()))),
                    new GlbStructure.Mesh(1, "duplicate", List.of(
                            new GlbStructure.Primitive(0, 0, List.of("POSITION"), List.of())))),
            List.of(), List.of(), List.of(),
            List.of(new GlbStructure.PrimitiveOccurrence(0, 1, 0, "duplicate", 0),
                    new GlbStructure.PrimitiveOccurrence(1, 1, 0, "duplicate", 0)));

    @Test
    void acceptsCompleteOccurrencesWithOneMaterialAndMultipleAllowedColors() {
        MeshConfigurationRequest request = request(List.of(
                mesh(0, 1, 0, true, 5, List.of(5, 8)),
                mesh(1, 1, 0, false, 5, List.of())));

        validator.validate(PRODUCT_ID, MEDIA_ASSET_ID, SNAPSHOT_ID, request, structure);

        assertThat(request.meshes().get(0).identity(PRODUCT_ID, MEDIA_ASSET_ID))
                .isEqualTo(new MeshPrimitiveIdentity(42, 901L, 0, 1, 0));
    }

    @Test
    void rejectsMissingOccurrence() {
        assertInvalid(request(List.of(mesh(0, 1, 0, true, 5, List.of(5)))), "exactly once");
    }

    @Test
    void rejectsDuplicateOccurrenceEvenWhenNamesAreTheSame() {
        assertInvalid(request(List.of(mesh(0, 1, 0, true, 5, List.of(5)),
                mesh(0, 1, 0, true, 5, List.of(5)))), "duplicate primitive identity");
    }

    @Test
    void rejectsUnknownOccurrence() {
        assertInvalid(request(List.of(mesh(0, 1, 9, true, 5, List.of(5)),
                mesh(1, 1, 0, false, 5, List.of()))), "unknown primitive identity");
    }

    @Test
    void customizableSelectionRequiresDefaultAmongNonemptyAllowedColors() {
        assertInvalid(request(List.of(mesh(0, 1, 0, true, 5, List.of(8)),
                mesh(1, 1, 0, false, 5, List.of()))), "defaultColorId must be allowed");
        assertInvalid(request(List.of(mesh(0, 1, 0, true, 5, List.of()),
                mesh(1, 1, 0, false, 5, List.of()))), "require allowed colors");
    }

    @Test
    void nonCustomizableSelectionCannotHaveAllowedColors() {
        assertInvalid(request(List.of(mesh(0, 1, 0, true, 5, List.of(5)),
                mesh(1, 1, 0, false, 5, List.of(5)))), "cannot have allowed colors");
    }

    @Test
    void rejectsDuplicateOrNonPositiveColorIds() {
        assertInvalid(request(List.of(mesh(0, 1, 0, true, 5, List.of(5, 5)),
                mesh(1, 1, 0, false, 5, List.of()))), "duplicate allowed color ID");
        assertInvalid(request(List.of(mesh(0, 1, 0, true, 5, List.of(0)),
                mesh(1, 1, 0, false, 5, List.of()))), "must be positive");
    }

    @Test
    void rejectsInvalidEnvelopeAndStaleSnapshot() {
        MeshConfigurationRequest valid = request(List.of(mesh(0, 1, 0, true, 5, List.of(5)),
                mesh(1, 1, 0, false, 5, List.of())));
        assertThatThrownBy(() -> validator.validate(0, MEDIA_ASSET_ID, SNAPSHOT_ID, valid, structure))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("productId");
        assertThatThrownBy(() -> validator.validate(PRODUCT_ID, MEDIA_ASSET_ID, SNAPSHOT_ID,
                new MeshConfigurationRequest(MEDIA_ASSET_ID, SNAPSHOT_ID, -1L, 12,
                        valid.meshes()), structure))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expectedRevision");
        assertThatThrownBy(() -> validator.validate(PRODUCT_ID, MEDIA_ASSET_ID, SNAPSHOT_ID,
                new MeshConfigurationRequest(MEDIA_ASSET_ID, SNAPSHOT_ID, Long.MAX_VALUE, 12,
                        valid.meshes()), structure))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expectedRevision");
        assertThatThrownBy(() -> validator.validate(PRODUCT_ID, MEDIA_ASSET_ID, UUID.randomUUID(), valid, structure))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stale");
        assertThatThrownBy(() -> validator.validate(PRODUCT_ID, MEDIA_ASSET_ID, SNAPSHOT_ID,
                new MeshConfigurationRequest(902L, SNAPSHOT_ID, 4L, 12, valid.meshes()), structure))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mediaAssetId is stale");
        assertInvalid(request(List.of(mesh(-1, 1, 0, true, 5, List.of(5)),
                mesh(1, 1, 0, false, 5, List.of()))), "indices must be non-negative");
    }

    @Test
    void requestDefensivelyCopiesListsAndPreservesProposalFieldTypes() {
        List<Integer> colors = new java.util.ArrayList<>(List.of(5, 8));
        MeshConfigurationRequest.Mesh configured = mesh(0, 1, 0, true, 5, colors);
        List<MeshConfigurationRequest.Mesh> meshes = new java.util.ArrayList<>(List.of(configured));
        MeshConfigurationRequest request = request(meshes);
        colors.add(13);
        meshes.clear();

        assertThat(request.mediaAssetId()).isEqualTo(901L);
        assertThat(request.structureSnapshotId()).isEqualTo(SNAPSHOT_ID);
        assertThat(request.expectedRevision()).isEqualTo(4L);
        assertThat(request.materialId()).isEqualTo(12);
        assertThat(request.meshes()).containsExactly(configured);
        assertThat(configured.allowedColorIds()).containsExactly(5, 8);
    }

    private void assertInvalid(MeshConfigurationRequest request, String message) {
        assertThatThrownBy(() -> validator.validate(PRODUCT_ID, MEDIA_ASSET_ID, SNAPSHOT_ID, request, structure))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }

    private static MeshConfigurationRequest request(List<MeshConfigurationRequest.Mesh> meshes) {
        return new MeshConfigurationRequest(MEDIA_ASSET_ID, SNAPSHOT_ID, 4L, 12, meshes);
    }

    private static MeshConfigurationRequest.Mesh mesh(int node, int mesh, int primitive,
                                                       boolean customizable, int defaultColor,
                                                       List<Integer> allowedColors) {
        return new MeshConfigurationRequest.Mesh(node, mesh, primitive, customizable,
                defaultColor, allowedColors);
    }
}
