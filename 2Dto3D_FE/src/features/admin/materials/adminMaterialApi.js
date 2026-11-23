import apiClient from '../../../services/api/apiClient.js';
import { ENDPOINTS } from '../../../services/api/endpoints.js';
import { isAdminColorList, isAdminMaterialList } from './adminMaterialContract.js';

export function createAdminMaterialApi(client = apiClient) {
  return {
    async listAdminColors({ signal } = {}) {
      const result = await client.get(ENDPOINTS.ADMIN_MATERIALS.COLORS, { signal });
      if (!isAdminColorList(result)) throw new Error('Backend trả về danh sách màu không đúng hợp đồng.');
      return result;
    },
    async listAdminMaterials({ signal } = {}) {
      const result = await client.get(ENDPOINTS.ADMIN_MATERIALS.LIST, { signal });
      if (!isAdminMaterialList(result)) throw new Error('Backend trả về danh sách vật liệu không đúng hợp đồng.');
      return result;
    },
    createAdminColor(values) {
      return client.post(ENDPOINTS.ADMIN_MATERIALS.CREATE_COLOR, values);
    },
    createAdminMaterial(values) {
      return client.post(ENDPOINTS.ADMIN_MATERIALS.CREATE, values);
    },
    updateAdminMaterial(id, values) {
      return client.put(ENDPOINTS.ADMIN_MATERIALS.UPDATE(id), values);
    },
  };
}

const api = createAdminMaterialApi();
export const listAdminColors = (...args) => api.listAdminColors(...args);
export const listAdminMaterials = (...args) => api.listAdminMaterials(...args);
export const createAdminColor = (...args) => api.createAdminColor(...args);
export const createAdminMaterial = (...args) => api.createAdminMaterial(...args);
export const updateAdminMaterial = (...args) => api.updateAdminMaterial(...args);
