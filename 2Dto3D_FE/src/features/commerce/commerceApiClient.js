import apiClient from '../../services/api/apiClient';
import { createCommerceApi } from './commerceApi.js';

export const commerceApi = createCommerceApi(apiClient);
