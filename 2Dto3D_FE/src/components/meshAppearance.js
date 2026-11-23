function identityKey(nodeIndex, meshIndex, primitiveIndex) {
    return `${nodeIndex}:${meshIndex}:${primitiveIndex}`;
}

function alignedObjects(source, clone) {
    const sources = [];
    const clones = [];
    source?.traverse?.(object => sources.push(object));
    clone?.traverse?.(object => clones.push(object));
    if (sources.length !== clones.length) return [];
    return sources.map((object, index) => [object, clones[index]]);
}

/** Maps only unique GLTFLoader parser occurrences; names and traversal labels are never identities. */
export function mapGltfMeshAppearanceTargets(sourceScene, clonedScene, associations) {
    const candidates = new Map();
    for (const [source, cloned] of alignedObjects(sourceScene, clonedScene)) {
        if (!source?.isMesh || !source.geometry) continue;
        const primitive = associations?.get?.(source);
        if (!Number.isSafeInteger(primitive?.primitives)) continue;
        let nodeIndex = null;
        let meshIndex = Number.isSafeInteger(primitive?.meshes) ? primitive.meshes : null;
        for (let ancestor = source; ancestor; ancestor = ancestor.parent) {
            const nodeAssociation = associations?.get?.(ancestor);
            if (meshIndex == null && Number.isSafeInteger(nodeAssociation?.meshes)) meshIndex = nodeAssociation.meshes;
            if (Number.isSafeInteger(nodeAssociation?.nodes)) { nodeIndex = nodeAssociation.nodes; break; }
        }
        if (nodeIndex == null || meshIndex == null) continue;
        const key = identityKey(nodeIndex, meshIndex, primitive.primitives);
        candidates.set(key, [...(candidates.get(key) || []), cloned]);
    }
    return new Map([...candidates].filter(([, values]) => values.length === 1).map(([key, values]) => [key, values[0]]));
}

/** Applies allowlisted colors to cloned materials on exact configurable primitive occurrences only. */
export function applyMeshAppearanceColors(targets, appearances, selections = {}) {
    if (!(targets instanceof Map) || !Array.isArray(appearances)) return;
    for (const appearance of appearances) {
        if (!appearance?.customizable) continue;
        const key = identityKey(appearance.nodeIndex, appearance.meshIndex, appearance.primitiveIndex);
        const target = targets.get(key);
        if (!target?.isMesh || !Array.isArray(appearance.allowedColors)) continue;
        const requested = appearance.allowedColors.find(color => String(color.id) === String(selections[key]));
        const selected = requested || appearance.allowedColors.find(color => color.id === appearance.defaultColor?.id);
        if (!selected || !/^#[\da-f]{6}$/i.test(selected.hexCode || '')) continue;
        const sourceMaterials = Array.isArray(target.material) ? target.material : [target.material];
        const clonedMaterials = sourceMaterials.map(material => material?.clone?.() || material);
        clonedMaterials.forEach(material => {
            if (!material?.color?.set) return;
            material.color.set(selected.hexCode);
            material.needsUpdate = true;
        });
        target.material = Array.isArray(target.material) ? clonedMaterials : clonedMaterials[0];
    }
}
