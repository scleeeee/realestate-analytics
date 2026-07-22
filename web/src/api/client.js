export async function apiGet(path) {
  const response = await fetch(path);
  if (!response.ok) {
    throw new Error(`API request failed: ${response.status} ${path}`);
  }
  return response.json();
}
