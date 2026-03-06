// Same gateway as plugin (default http://localhost:8080 via proxy or VITE_API_BASE_URL) so metrics stay in sync.
export const API_BASE = import.meta.env.VITE_API_BASE_URL ?? '';
export const API = `${API_BASE}/api/v1/prompts`;
export const HEALTH_URL = `${API_BASE}/q/health`;
