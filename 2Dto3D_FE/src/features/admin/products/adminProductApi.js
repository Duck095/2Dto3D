import apiClient from '../../../services/api/apiClient.js';
import { ENDPOINTS } from '../../../services/api/endpoints.js';
import { buildMetadataPayload, isAdminProduct, isAdminProductPage, isMaterialList, isMeshStructureResponse, isMeshConfigurationResponse, isPublishedAdminProductMedia, isAdminProductPrintAssetList, validateThreeMfFile } from './adminProductContract.js';

function requireProduct(value) {
  if (!isAdminProduct(value)) throw new Error('Backend trả về dữ liệu sản phẩm không đúng hợp đồng.');
  return value;
}

export async function listAdminProducts({ page = 0, size = 20, q = '', status = '' } = {}, { signal } = {}) {
  const result = await apiClient.get(ENDPOINTS.ADMIN_PRODUCTS.LIST, {
    params: { page, size, ...(q ? { q } : {}), ...(status ? { status } : {}) }, signal,
  });
  if (!isAdminProductPage(result)) throw new Error('Backend trả về danh sách sản phẩm không đúng hợp đồng.');
  return result;
}

export async function getAdminProduct(id, { signal } = {}) {
  return requireProduct(await apiClient.get(ENDPOINTS.ADMIN_PRODUCTS.DETAIL(id), { signal }));
}

export function createAdminProductMediaApi(client = apiClient) {
  return {
    async getPublishedAdminProductMedia(id, { signal } = {}) {
      const product = await client.get(ENDPOINTS.PRODUCTS.DETAIL(id), { signal });
      if (!isPublishedAdminProductMedia(product, id)) {
        throw new Error('Backend chưa cung cấp GLB công khai hợp lệ cho sản phẩm đã xuất bản.');
      }
      return { productId: Number(id), model3dUrl: product.model3dUrl };
    },
  };
}

const productMediaApi = createAdminProductMediaApi();
export const getPublishedAdminProductMedia = (...args) => productMediaApi.getPublishedAdminProductMedia(...args);

export async function createAdminProductDraft(values, images, model) {
  const body = new FormData();
  body.append('data', new Blob([JSON.stringify(buildMetadataPayload(values))], { type: 'application/json' }));
  images.forEach(image => body.append('images', image, image.name));
  body.append('model', model, model.name);
  return requireProduct(await apiClient.post(ENDPOINTS.ADMIN_PRODUCTS.LIST, body, {
    headers: { 'Content-Type': undefined },
  }));
}

export async function updateAdminProduct(id, values) {
  const result = await apiClient.patch(ENDPOINTS.ADMIN_PRODUCTS.DETAIL(id), buildMetadataPayload(values));
  return requireProduct(result);
}

export const publishAdminProduct = id => apiClient.post(ENDPOINTS.ADMIN_PRODUCTS.PUBLISH(id));
export const archiveAdminProduct = id => apiClient.post(ENDPOINTS.ADMIN_PRODUCTS.ARCHIVE(id));

export async function listAdminMaterials({ signal } = {}) {
  const result = await apiClient.get(ENDPOINTS.ADMIN_MATERIALS.LIST, { signal });
  if (!isMaterialList(result)) throw new Error('Backend trả về danh sách vật liệu không đúng hợp đồng.');
  return result;
}

export function createAdminProductMeshApi(client = apiClient) {
  return {
    async getStructure(productId, { signal } = {}) {
      const result = await client.get(ENDPOINTS.ADMIN_PRODUCTS.MESH_STRUCTURE(productId), { signal });
      if (!isMeshStructureResponse(result, Number(productId))) throw new Error('Backend trả về cấu trúc mesh không đúng hợp đồng.');
      return result;
    },
    async getConfiguration(productId, structure, { signal } = {}) {
      const result = await client.get(ENDPOINTS.ADMIN_PRODUCTS.MESH_CONFIGURATIONS(productId), { signal });
      if (!isMeshConfigurationResponse(result, structure)) throw new Error('Backend trả về cấu hình mesh không đúng hợp đồng.');
      return result;
    },
    async putConfiguration(productId, payload, structure, { signal } = {}) {
      const result = await client.put(ENDPOINTS.ADMIN_PRODUCTS.MESH_CONFIGURATIONS(productId), payload, { signal });
      if (!isMeshConfigurationResponse(result, structure)) throw new Error('Backend trả về kết quả lưu cấu hình mesh không đúng hợp đồng.');
      return result;
    },
  };
}

const meshApi = createAdminProductMeshApi();
export const getAdminProductMeshStructure = (...args) => meshApi.getStructure(...args);
export const getAdminProductMeshConfiguration = (...args) => meshApi.getConfiguration(...args);
export const putAdminProductMeshConfiguration = (...args) => meshApi.putConfiguration(...args);

export function createAdminProductPrintAssetApi(client = apiClient) {
  return {
    async upload(productId, file) {
      const validationError = validateThreeMfFile(file);
      if (validationError) throw new Error(validationError);
      const body = new FormData();
      body.append('file', file, file.name);
      const result = await client.post(ENDPOINTS.ADMIN_PRODUCTS.PRINT_ASSETS(productId), body, { headers: { 'Content-Type': undefined } });
      if (!isAdminProductPrintAssetList([result])) throw new Error('Backend trả về metadata tệp 3MF không đúng hợp đồng.');
      return result;
    },
    async list(productId, { signal } = {}) {
      const result = await client.get(ENDPOINTS.ADMIN_PRODUCTS.PRINT_ASSETS(productId), { signal });
      if (!isAdminProductPrintAssetList(result)) throw new Error('Backend trả về lịch sử tệp 3MF không đúng hợp đồng.');
      return result;
    },
  };
}

const printAssetApi = createAdminProductPrintAssetApi();
export const uploadAdminProductPrintAsset = (...args) => printAssetApi.upload(...args);
export const listAdminProductPrintAssets = (...args) => printAssetApi.list(...args);
