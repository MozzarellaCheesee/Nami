// API-утилиты для работы с сервером
const API_BASE = '/api';

function getToken() {
  return localStorage.getItem('nami_token');
}

async function fetchAPI(path, options = {}) {
  const token = getToken();
  const headers = {
    'Content-Type': 'application/json',
    ...options.headers
  };

  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }

  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers
  });

  if (!response.ok) {
    const error = await response.text();
    throw new Error(error || `HTTP ${response.status}`);
  }

  return response.json();
}

export const api = {
  // Auth
  async login(username, password) {
    const data = await fetchAPI('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password })
    });
    if (data.token) {
      localStorage.setItem('nami_token', data.token);
    }
    return data;
  },

  async logout() {
    await fetchAPI('/auth/logout', { method: 'POST' });
    localStorage.removeItem('nami_token');
  },

  async getMe() {
    return fetchAPI('/me');
  },

  // Users
  async getUsers() {
    return fetchAPI('/users');
  },

  async deleteUser(id) {
    return fetchAPI(`/users/${id}`, { method: 'DELETE' });
  },

  async getUserFolders(id) {
    return fetchAPI(`/users/${id}/folders`);
  },

  async setUserFolders(id, folders) {
    return fetchAPI(`/users/${id}/folders`, {
      method: 'PUT',
      body: JSON.stringify(folders)
    });
  },

  // Invites
  async createInvite(role, library_id, ttl_secs) {
    return fetchAPI('/invites', {
      method: 'POST',
      body: JSON.stringify({ role, library_id, ttl_secs })
    });
  },

  // Scanner
  async triggerScan() {
    return fetchAPI('/scan', { method: 'POST' });
  }
};
