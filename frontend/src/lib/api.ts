import axios from 'axios';

const API_BASE = import.meta.env.VITE_API_URL || '/api/v1';

export const apiClient = axios.create({
  baseURL: API_BASE,
  timeout: 30000,
});

apiClient.interceptors.request.use((config) => {
  const token = localStorage.getItem('agentops_token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('agentops_token');
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);

export const authApi = {
  login: (apiKey: string) =>
    apiClient.post<{ token: string; expiresIn: number; tokenType: string }>('/auth/login', { apiKey }),
  refresh: (token: string) =>
    apiClient.post<{ token: string; expiresIn: number }>('/auth/refresh', null, {
      headers: { Authorization: `Bearer ${token}` },
    }),
};

export const diagnosisApi = {
  getSession: (sessionId: string) =>
    apiClient.get(`/diagnosis/${sessionId}`),
  getTrace: (sessionId: string) =>
    apiClient.get(`/diagnosis/${sessionId}/trace`),
  getTimeline: (sessionId: string) =>
    apiClient.get(`/diagnosis/${sessionId}/timeline`),
  getSqlAudit: (sessionId: string) =>
    apiClient.get(`/diagnosis/${sessionId}/sql-audit`),
  getHistory: (page = 0, size = 20) =>
    apiClient.get(`/diagnosis/history?page=${page}&size=${size}`),
};

export const knowledgeApi = {
  list: (category?: string) =>
    apiClient.get(`/knowledge${category ? `?category=${category}` : ''}`),
  search: (keyword: string, limit = 5) =>
    apiClient.get(`/knowledge/search?keyword=${keyword}&limit=${limit}`),
  get: (id: number) =>
    apiClient.get(`/knowledge/${id}`),
  create: (data: Record<string, unknown>) =>
    apiClient.post('/knowledge', data),
  update: (id: number, data: Record<string, unknown>) =>
    apiClient.put(`/knowledge/${id}`, data),
  delete: (id: number) =>
    apiClient.delete(`/knowledge/${id}`),
};

export const healthApi = {
  getHealth: () =>
    apiClient.get('/actuator/health'),
};
