package com.exe.morphia3d.dto.admin3d.mesh;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MeshAdminDtoContractTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void structureResponseUsesProposalFieldsAndExcludesBinaryOrProviderLocations() throws Exception {
        MeshStructureResponse response = new MeshStructureResponse(42, 901L,
                UUID.fromString("84bc80c3-7d63-4a65-a647-35737cf19fc8"), "glb-structure-1", 0,
                List.of(new MeshStructureResponse.Scene(0, "Main", List.of(0))),
                List.of(new MeshStructureResponse.Node(0, "HandleNode", 1, List.of(), List.of(0))),
                List.of(new MeshStructureResponse.Mesh(1, "Handle", List.of(
                        new MeshStructureResponse.Primitive(0, 2, List.of("POSITION"), List.of(0))))),
                List.of(new MeshStructureResponse.PrimitiveOccurrence(0, 1, 0, "HandleNode", 2)),
                List.of(new MeshStructureResponse.Material(2, "Base", List.of(0))),
                List.of(new MeshStructureResponse.Texture(0, List.of(0), null)),
                List.of(new MeshStructureResponse.Image(0, "albedo", "image/png", false, 0)));

        JsonNode json = objectMapper.valueToTree(response);

        assertThat(json.has("productId")).isTrue();
        assertThat(json.has("mediaAssetId")).isTrue();
        assertThat(json.has("structureSnapshotId")).isTrue();
        assertThat(json.has("parserVersion")).isTrue();
        assertThat(json.has("defaultSceneIndex")).isTrue();
        assertThat(json.has("primitiveOccurrences")).isTrue();
        assertThat(json.has("images")).isTrue();
        JsonNode occurrence = json.get("primitiveOccurrences").get(0);
        assertThat(occurrence.has("nodeIndex")).isTrue();
        assertThat(occurrence.has("meshIndex")).isTrue();
        assertThat(occurrence.has("primitiveIndex")).isTrue();
        assertThat(occurrence.has("originalName")).isTrue();
        assertThat(occurrence.has("materialIndex")).isTrue();
        assertThat(json.toString()).doesNotContain("secureUrl", "https://", "data:");
    }

    @Test
    void configurationRequestAndResponseUseProposalSelectionShape() throws Exception {
        UUID snapshotId = UUID.fromString("84bc80c3-7d63-4a65-a647-35737cf19fc8");
        MeshConfigurationRequest request = new MeshConfigurationRequest(901L, snapshotId, 4L, 12,
                List.of(new MeshConfigurationRequest.Mesh(0, 1, 0, true, 5, List.of(5, 8))));
        MeshConfigurationResponse response = new MeshConfigurationResponse(42, 901L, snapshotId, 5L,
                true, 12, List.of(new MeshConfigurationResponse.Mesh(0, 1, 0, "HandleNode", true,
                new MeshConfigurationResponse.Selection(12, 5), List.of(
                new MeshConfigurationResponse.Selection(12, 5),
                new MeshConfigurationResponse.Selection(12, 8)))));

        JsonNode requestJson = objectMapper.valueToTree(request);
        JsonNode responseJson = objectMapper.valueToTree(response);

        assertThat(requestJson.has("mediaAssetId")).isTrue();
        assertThat(requestJson.has("structureSnapshotId")).isTrue();
        assertThat(requestJson.has("expectedRevision")).isTrue();
        assertThat(requestJson.has("materialId")).isTrue();
        assertThat(requestJson.has("meshes")).isTrue();
        JsonNode requestMesh = requestJson.get("meshes").get(0);
        assertThat(requestMesh.has("nodeIndex")).isTrue();
        assertThat(requestMesh.has("meshIndex")).isTrue();
        assertThat(requestMesh.has("primitiveIndex")).isTrue();
        assertThat(requestMesh.has("customizable")).isTrue();
        assertThat(requestMesh.has("defaultColorId")).isTrue();
        assertThat(requestMesh.has("allowedColorIds")).isTrue();
        JsonNode selection = responseJson.get("meshes").get(0).get("defaultSelection");
        assertThat(selection.has("materialId")).isTrue();
        assertThat(selection.has("colorId")).isTrue();
        assertThat(responseJson.get("meshes").get(0).get("allowedSelections")).hasSize(2);
    }
}
