const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8080';

export async function apiGet(path) {
  const response = await fetch(`${API_BASE_URL}${path}`);
  if (!response.ok) {
    throw new Error(`API request failed: ${response.status} ${path}`);
  }
  return response.json();
}
