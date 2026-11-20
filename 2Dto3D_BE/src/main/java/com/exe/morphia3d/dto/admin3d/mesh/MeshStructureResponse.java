package com.exe.morphia3d.dto.admin3d.mesh;

import java.util.List;
import java.util.UUID;

/** Metadata response for GET /api/admin/products/{productId}/mesh-structure. */
public record MeshStructureResponse(
        Integer productId,
        Long mediaAssetId,
        UUID structureSnapshotId,
        String parserVersion,
        Integer defaultSceneIndex,
        List<Scene> scenes,
        List<Node> nodes,
        List<Mesh> meshes,
        List<PrimitiveOccurrence> primitiveOccurrences,
        List<Material> materials,
        List<Texture> textures,
        List<Image> images) {

    public MeshStructureResponse {
        scenes = copy(scenes);
        nodes = copy(nodes);
        meshes = copy(meshes);
        primitiveOccurrences = copy(primitiveOccurrences);
        materials = copy(materials);
        textures = copy(textures);
        images = copy(images);
    }

    public record Scene(Integer sceneIndex, String name, List<Integer> rootNodeIndices) {
        public Scene { rootNodeIndices = copy(rootNodeIndices); }
    }

    public record Node(Integer nodeIndex, String nodeName, Integer meshIndex,
                       List<Integer> childNodeIndices, List<Integer> sceneIndices) {
        public Node {
            childNodeIndices = copy(childNodeIndices);
            sceneIndices = copy(sceneIndices);
        }
    }

    public record Mesh(Integer meshIndex, String meshName, List<Primitive> primitives) {
        public Mesh { primitives = copy(primitives); }
    }

    public record Primitive(Integer primitiveIndex, Integer materialIndex,
                            List<String> attributeNames, List<Integer> textureIndices) {
        public Primitive {
            attributeNames = copy(attributeNames);
            textureIndices = copy(textureIndices);
        }
    }

    public record PrimitiveOccurrence(Integer nodeIndex, Integer meshIndex, Integer primitiveIndex,
                                      String originalName, Integer materialIndex) {
    }

    public record Material(Integer materialIndex, String name, List<Integer> textureIndices) {
        public Material { textureIndices = copy(textureIndices); }
    }

    public record Texture(Integer textureIndex, List<Integer> sourceImageIndices, Integer samplerIndex) {
        public Texture { sourceImageIndices = copy(sourceImageIndices); }
    }

    /** Image URI values and payloads are deliberately excluded. */
    public record Image(Integer imageIndex, String name, String mimeType,
                        boolean uriSource, Integer bufferViewIndex) {
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? null : List.copyOf(values);
    }
}
