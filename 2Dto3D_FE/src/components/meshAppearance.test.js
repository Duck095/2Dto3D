import assert from 'node:assert/strict';
import test from 'node:test';
import { Color, Mesh, MeshStandardMaterial, BoxGeometry, Group } from 'three';
import { applyMeshAppearanceColors, mapGltfMeshAppearanceTargets } from './meshAppearance.js';

test('maps parser node/mesh/primitive associations by exact occurrence triple and fails closed on duplicates', () => {
  const source = new Group();
  const node = new Group(); source.add(node);
  const mesh = new Mesh(new BoxGeometry(), new MeshStandardMaterial()); node.add(mesh);
  const cloned = source.clone(true);
  const target = mapGltfMeshAppearanceTargets(source, cloned, new Map([
    [node, { nodes: 5 }], [mesh, { meshes: 7, primitives: 2 }],
  ]));
  assert.equal(target.get('5:7:2'), cloned.children[0].children[0]);
  const duplicate = new Mesh(new BoxGeometry(), new MeshStandardMaterial()); node.add(duplicate);
  const duplicateClone = source.clone(true);
  const ambiguous = mapGltfMeshAppearanceTargets(source, duplicateClone, new Map([
    [node, { nodes: 5 }], [mesh, { meshes: 7, primitives: 2 }], [duplicate, { meshes: 7, primitives: 2 }],
  ]));
  assert.equal(ambiguous.has('5:7:2'), false);
});

test('changes only allowed customizable occurrence colors and leaves unrelated PBR materials untouched', () => {
  const customizable = new Mesh(new BoxGeometry(), new MeshStandardMaterial({ color: '#334455', roughness: 0.42, metalness: 0.2 }));
  const locked = new Mesh(new BoxGeometry(), new MeshStandardMaterial({ color: '#778899', roughness: 0.7, metalness: 0.1 }));
  const originals = [customizable.material, locked.material];
  applyMeshAppearanceColors(new Map([['0:0:0', customizable], ['0:1:0', locked]]), [
    { nodeIndex: 0, meshIndex: 0, primitiveIndex: 0, customizable: true, defaultColor: { id: 8, hexCode: '#123456' }, allowedColors: [{ id: 8, hexCode: '#123456' }, { id: 9, hexCode: '#ABCDEF' }] },
    { nodeIndex: 0, meshIndex: 1, primitiveIndex: 0, customizable: false, defaultColor: { id: 8, hexCode: '#123456' }, allowedColors: [] },
  ], { '0:0:0': 99, '0:1:0': 9 });
  assert.notEqual(customizable.material, originals[0]);
  assert.equal(customizable.material.color.getHexString(), '123456');
  assert.equal(customizable.material.roughness, 0.42);
  assert.equal(customizable.material.metalness, 0.2);
  assert.equal(locked.material, originals[1]);
  assert.equal(locked.material.color.getHexString(), '778899');
});
