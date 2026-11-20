import apiClient from '../../../services/api/apiClient';
import { ENDPOINTS } from '../../../services/api/endpoints';

const cleanParams = params => Object.fromEntries(
  Object.entries(params || {}).filter(([, value]) => value !== '' && value !== null && value !== undefined),
);

export const listMyTasks = (params, { signal } = {}) =>
  apiClient.get(ENDPOINTS.EMPLOYEE.TASKS, { params: cleanParams(params), signal });
export const getMyTask = (id, { signal } = {}) =>
  apiClient.get(ENDPOINTS.EMPLOYEE.TASK(id), { signal });
export const startMyTask = (id, expectedVersion) =>
  apiClient.post(ENDPOINTS.EMPLOYEE.START_TASK(id), { expectedVersion });
export const submitMyTask = (id, payload) =>
  apiClient.post(ENDPOINTS.EMPLOYEE.SUBMISSIONS(id), {
    expectedVersion: payload.expectedVersion,
    resultDescription: payload.resultDescription,
    resultUrl: payload.resultUrl || null,
  });

export function getEmployeeTaskErrorMessage(error) {
  const status = error?.response?.status;
  if (status === 400) return 'Thông tin gửi lên chưa hợp lệ. Hãy kiểm tra và thử lại.';
  if (status === 401) return 'Phiên đăng nhập không còn hợp lệ. Hãy đăng nhập lại.';
  if (status === 403) return 'Tài khoản chưa được xác minh quyền nhân viên.';
  if (status === 404) return 'Không tìm thấy công việc được giao cho tài khoản này.';
  if (status === 409) return 'Công việc đã thay đổi. Dữ liệu mới nhất đã được tải lại.';
  return 'Không thể tải hoặc cập nhật công việc. Vui lòng thử lại.';
}
