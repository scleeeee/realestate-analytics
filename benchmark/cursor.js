export function encodeCursor(dealYm, id) {
  return Buffer.from(`${dealYm}:${id}`).toString('base64url');
}
