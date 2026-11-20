package com.exe.morphia3d.entity;

import com.exe.morphia3d.service.glb.GlbStructure;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Set;

/** Strict readers for the versioned JSON documents stored by the immutable Admin 3D history tables. */
final class Admin3dHistoryJson {
    static final String SUPPORTED_PARSER_VERSION = ProductGlbStructureSnapshot.CURRENT_PARSER_VERSION;
    static final int CONFIGURATION_SCHEMA_VERSION = ProductMeshConfigurationRevision.CONFIGURATION_JSON_SCHEMA_VERSION;
    private static final int MAX_STRUCTURAL_ITEMS = 100_000;
    private static final long MAX_TOTAL_ALLOWED_COLORS = 100_000L;
    private static final ObjectMapper STRICT_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private Admin3dHistoryJson() {}

    static JsonNode validateNewStructure(String parserVersion, JsonNode json) {
        requireSupportedParserVersion(parserVersion, false);
        readStructure(json, false);
        return objectCopy(json, "structureJson", false);
    }

    static JsonNode validateNewStructure(String parserVersion, GlbStructure structure) {
        if (structure == null) throw new IllegalArgumentException("structureJson is required");
        return validateNewStructure(parserVersion, STRICT_MAPPER.valueToTree(structure));
    }

    static GlbStructure readStructure(String parserVersion, JsonNode json) {
        requireSupportedParserVersion(parserVersion, true);
        return readStructure(json, true);
    }

    private static GlbStructure readStructure(JsonNode json, boolean stored) {
        JsonNode object = requireObject(json, "structureJson", stored);
        for (String field : new String[]{"scenes", "nodes", "meshes", "materials", "textures", "images", "primitiveOccurrences"}) {
            JsonNode array = object.get(field);
            if (array == null || !array.isArray()) {
                throw invalid(stored, "Stored GLB structure field must be an array: " + field);
            }
            if (array.size() > MAX_STRUCTURAL_ITEMS) {
                throw invalid(stored, "Stored GLB structure exceeds the supported item limit: " + field);
            }
        }
        JsonNode defaultScene = object.get("defaultSceneIndex");
        if (defaultScene == null || !defaultScene.isIntegralNumber() || !defaultScene.canConvertToInt()) {
            throw invalid(stored, "Stored GLB structure defaultSceneIndex is invalid");
        }
        try {
            GlbStructure structure = STRICT_MAPPER.treeToValue(object, GlbStructure.class);
            validateStructureSemantics(structure, stored);
            return structure;
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException exception) {
            throw invalid(stored, "Stored GLB structure JSON is corrupt", exception);
        }
    }

    static JsonNode validateNewConfiguration(JsonNode json) {
        validateConfiguration(json, false);
        return objectCopy(json, "configurationJson", false);
    }

    static JsonNode readConfiguration(JsonNode json) {
        validateConfiguration(json, true);
        return json.deepCopy();
    }

    private static void validateConfiguration(JsonNode json, boolean stored) {
        JsonNode object = requireObject(json, "configurationJson", stored);
        requireOnlyFields(object, Set.of("schemaVersion", "meshes"), "configuration", stored);
        JsonNode version = object.get("schemaVersion");
        if (version == null || !version.isIntegralNumber() || !version.canConvertToInt()) {
            throw invalid(stored, "Mesh configuration JSON schemaVersion is invalid");
        }
        if (version.intValue() != CONFIGURATION_SCHEMA_VERSION) {
            throw invalid(stored, "Unsupported mesh configuration JSON schema version: " + version.intValue());
        }
        JsonNode meshes = object.get("meshes");
        if (meshes == null || !meshes.isArray()) {
            throw invalid(stored, "Mesh configuration JSON meshes must be an array");
        }
        if (meshes.size() > MAX_STRUCTURAL_ITEMS) {
            throw invalid(stored, "Mesh configuration JSON exceeds the supported mesh limit");
        }
        Set<String> identities = new HashSet<>();
        long totalAllowedColors = 0;
        for (JsonNode mesh : meshes) {
            if (!mesh.isObject()) throw invalid(stored, "Mesh configuration JSON contains a non-object mesh");
            requireOnlyFields(mesh, Set.of("nodeIndex", "meshIndex", "primitiveIndex", "customizable",
                    "defaultColorId", "allowedColorIds"), "mesh configuration", stored);
            int nodeIndex = nonNegativeInt(mesh.get("nodeIndex"), "nodeIndex", stored);
            int meshIndex = nonNegativeInt(mesh.get("meshIndex"), "meshIndex", stored);
            int primitiveIndex = nonNegativeInt(mesh.get("primitiveIndex"), "primitiveIndex", stored);
            if (!identities.add(nodeIndex + ":" + meshIndex + ":" + primitiveIndex)) {
                throw invalid(stored, "Mesh configuration JSON contains a duplicate primitive identity");
            }
            JsonNode customizable = mesh.get("customizable");
            if (customizable == null || !customizable.isBoolean()) {
                throw invalid(stored, "Mesh configuration JSON customizable is invalid");
            }
            int defaultColorId = positiveInt(mesh.get("defaultColorId"), "defaultColorId", stored);
            JsonNode allowed = mesh.get("allowedColorIds");
            if (allowed == null || !allowed.isArray() || allowed.size() > MAX_STRUCTURAL_ITEMS) {
                throw invalid(stored, "Mesh configuration JSON allowedColorIds must be a bounded array");
            }
            totalAllowedColors = Math.addExact(totalAllowedColors, allowed.size());
            if (totalAllowedColors > MAX_TOTAL_ALLOWED_COLORS) {
                throw invalid(stored, "Mesh configuration JSON exceeds the total allowed-color limit");
            }
            Set<Integer> colorIds = new HashSet<>();
            for (JsonNode color : allowed) {
                int colorId = positiveInt(color, "allowedColorIds[]", stored);
                if (!colorIds.add(colorId)) throw invalid(stored, "Mesh configuration JSON has duplicate allowed colors");
            }
            if (customizable.booleanValue()) {
                if (colorIds.isEmpty() || !colorIds.contains(defaultColorId)) {
                    throw invalid(stored, "Customizable mesh default color must be present in allowedColorIds");
                }
            } else if (!colorIds.isEmpty()) {
                throw invalid(stored, "Non-customizable mesh cannot have allowedColorIds");
            }
        }
    }

    private static void validateStructureSemantics(GlbStructure structure, boolean stored) {
        if (structure == null) throw invalid(stored, "Stored GLB structure JSON is empty");
        int sceneCount = structure.scenes().size();
        if (structure.defaultSceneIndex() < -1 || structure.defaultSceneIndex() >= sceneCount) {
            throw invalid(stored, "Stored GLB structure default scene is out of range");
        }
        for (int i = 0; i < sceneCount; i++) {
            GlbStructure.Scene scene = structure.scenes().get(i);
            if (scene.index() != i) throw invalid(stored, "Stored GLB structure scene indices are inconsistent");
            for (Integer root : scene.rootNodeIndices()) requireIndex(root, structure.nodes().size(), "scene root", stored);
        }
        for (int i = 0; i < structure.nodes().size(); i++) {
            GlbStructure.Node node = structure.nodes().get(i);
            if (node.index() != i) throw invalid(stored, "Stored GLB structure node indices are inconsistent");
            if (node.meshIndex() != null) requireIndex(node.meshIndex(), structure.meshes().size(), "node mesh", stored);
            for (Integer child : node.childIndices()) requireIndex(child, structure.nodes().size(), "node child", stored);
            for (Integer scene : node.sceneIndices()) requireIndex(scene, sceneCount, "node scene", stored);
        }
        for (int i = 0; i < structure.meshes().size(); i++) {
            GlbStructure.Mesh mesh = structure.meshes().get(i);
            if (mesh.index() != i || mesh.primitives().isEmpty()) {
                throw invalid(stored, "Stored GLB structure mesh is inconsistent");
            }
            for (int j = 0; j < mesh.primitives().size(); j++) {
                GlbStructure.Primitive primitive = mesh.primitives().get(j);
                if (primitive.index() != j) throw invalid(stored, "Stored GLB structure primitive indices are inconsistent");
                if (primitive.materialIndex() != null) requireIndex(primitive.materialIndex(), structure.materials().size(), "primitive material", stored);
                for (Integer texture : primitive.textureIndices()) requireIndex(texture, structure.textures().size(), "primitive texture", stored);
            }
        }
        for (int i = 0; i < structure.materials().size(); i++) {
            GlbStructure.Material material = structure.materials().get(i);
            if (material.index() != i) throw invalid(stored, "Stored GLB structure material indices are inconsistent");
            for (Integer texture : material.textureIndices()) requireIndex(texture, structure.textures().size(), "material texture", stored);
        }
        for (int i = 0; i < structure.textures().size(); i++) {
            GlbStructure.Texture texture = structure.textures().get(i);
            if (texture.index() != i || texture.sourceImageIndices().isEmpty()) {
                throw invalid(stored, "Stored GLB structure texture is inconsistent");
            }
            for (Integer image : texture.sourceImageIndices()) requireIndex(image, structure.images().size(), "texture image", stored);
        }
        for (int i = 0; i < structure.images().size(); i++) {
            if (structure.images().get(i).index() != i) throw invalid(stored, "Stored GLB structure image indices are inconsistent");
        }
        Set<String> expectedOccurrences = new HashSet<>();
        for (GlbStructure.Node node : structure.nodes()) {
            if (node.meshIndex() == null) continue;
            GlbStructure.Mesh mesh = structure.meshes().get(node.meshIndex());
            for (GlbStructure.Primitive primitive : mesh.primitives()) {
                expectedOccurrences.add(node.index() + ":" + mesh.index() + ":" + primitive.index());
            }
        }
        if (structure.primitiveOccurrences().size() > MAX_STRUCTURAL_ITEMS
                || structure.primitiveOccurrences().size() != expectedOccurrences.size()) {
            throw invalid(stored, "Stored GLB structure primitive occurrences are incomplete");
        }
        for (GlbStructure.PrimitiveOccurrence occurrence : structure.primitiveOccurrences()) {
            if (occurrence.nodeIndex() < 0 || occurrence.nodeIndex() >= structure.nodes().size()
                    || occurrence.meshIndex() < 0 || occurrence.meshIndex() >= structure.meshes().size()
                    || occurrence.primitiveIndex() < 0
                    || occurrence.primitiveIndex() >= structure.meshes().get(occurrence.meshIndex()).primitives().size()
                    || !expectedOccurrences.remove(occurrence.nodeIndex() + ":" + occurrence.meshIndex() + ":" + occurrence.primitiveIndex())) {
                throw invalid(stored, "Stored GLB structure primitive occurrence identity is invalid");
            }
        }
    }

    private static void requireSupportedParserVersion(String parserVersion, boolean stored) {
        if (!SUPPORTED_PARSER_VERSION.equals(parserVersion)) {
            throw invalid(stored, "Unsupported GLB structure parser version: " + parserVersion);
        }
    }

    private static JsonNode objectCopy(JsonNode json, String field, boolean stored) {
        requireObject(json, field, stored);
        return json.deepCopy();
    }

    private static JsonNode requireObject(JsonNode json, String field, boolean stored) {
        if (json == null || !json.isObject()) throw invalid(stored, field + " must be a JSON object");
        return json;
    }

    private static void requireOnlyFields(JsonNode object, Set<String> allowed, String description, boolean stored) {
        object.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) throw invalid(stored, "Unsupported field in " + description + " JSON: " + field);
        });
    }

    private static int nonNegativeInt(JsonNode value, String field, boolean stored) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw invalid(stored, "Mesh configuration JSON " + field + " is invalid");
        }
        return value.intValue();
    }

    private static int positiveInt(JsonNode value, String field, boolean stored) {
        int result = nonNegativeInt(value, field, stored);
        if (result == 0) throw invalid(stored, "Mesh configuration JSON " + field + " must be positive");
        return result;
    }

    private static void requireIndex(Integer value, int size, String field, boolean stored) {
        if (value == null || value < 0 || value >= size) throw invalid(stored, "Stored GLB structure " + field + " is out of range");
    }

    private static RuntimeException invalid(boolean stored, String message) {
        return stored ? new IllegalStateException(message) : new IllegalArgumentException(message);
    }

    private static RuntimeException invalid(boolean stored, String message, Throwable cause) {
        return stored ? new IllegalStateException(message, cause) : new IllegalArgumentException(message, cause);
    }
}
