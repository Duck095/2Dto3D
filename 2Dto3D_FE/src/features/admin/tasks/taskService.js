import apiClient from '../../../services/api/apiClient';
import { ENDPOINTS } from '../../../services/api/endpoints';

const cleanParams = params => Object.fromEntries(
  Object.entries(params || {}).filter(([, value]) => value !== '' && value !== null && value !== undefined),
);

export const listTasks = (params, { signal } = {}) =>
  apiClient.get(ENDPOINTS.TASKS.LIST, { params: cleanParams(params), signal });

export const createTask = payload => apiClient.post(ENDPOINTS.TASKS.CREATE, payload);
export const getTask = (id, { signal } = {}) => apiClient.get(ENDPOINTS.TASKS.DETAIL(id), { signal });
export const updateTask = (id, payload) => apiClient.patch(ENDPOINTS.TASKS.UPDATE(id), payload);
export const listAcceptanceTasks = (params, { signal } = {}) =>
  apiClient.get(ENDPOINTS.TASKS.ACCEPTANCE, { params: cleanParams(params), signal });
export const reviewTask = (id, payload) => apiClient.post(ENDPOINTS.TASKS.REVIEW(id), payload);
export const getTaskSummary = ({ signal } = {}) => apiClient.get(ENDPOINTS.TASKS.SUMMARY, { signal });

export function getTaskApiErrorMessage(error) {
  const status = error?.response?.status;
  if (status === 400) return 'Thông tin công việc chưa hợp lệ. Hãy kiểm tra các trường rồi thử lại.';
  if (status === 401) return 'Phiên đăng nhập không còn hợp lệ. Hãy đăng nhập lại.';
  if (status === 403) return 'Tài khoản hiện tại không có quyền quản lý công việc.';
  if (status === 404) return 'Không tìm thấy công việc hoặc hồ sơ nhân viên được yêu cầu.';
  if (status === 409) return 'Công việc đã thay đổi hoặc không còn ở trạng thái này. Tải lại dữ liệu trước khi thử lại.';
  return 'Không thể hoàn tất yêu cầu công việc. Vui lòng thử lại.';
}
