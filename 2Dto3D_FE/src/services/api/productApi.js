import apiClient from './apiClient.js';
import { createProductApi } from './productApiClient.js';


export const productApi = createProductApi(apiClient);
