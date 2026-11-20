import apiClient from '../../services/api/apiClient';
import { ENDPOINTS } from '../../services/api/endpoints';

export const getMyStaffProfile = ({ signal } = {}) =>
  apiClient.get(ENDPOINTS.EMPLOYEE.STAFF_PROFILE, { signal });
