import { useCallback, useState } from 'react';
import { apiGet } from '../api/client';

export function useTransactionSearch() {
  const [items, setItems] = useState([]);
  const [nextCursor, setNextCursor] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const runSearch = useCallback(async (condition, cursor, append) => {
    setLoading(true);
    setError(null);
    try {
      const data = await apiGet(`/api/transactions?${buildParams(condition, cursor)}`);
      setItems((prev) => (append ? [...prev, ...data.items] : data.items));
      setNextCursor(data.nextCursor);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, []);

  const search = useCallback((condition) => runSearch(condition, null, false), [runSearch]);
  const loadMore = useCallback((condition) => runSearch(condition, nextCursor, true), [runSearch, nextCursor]);

  return { items, nextCursor, loading, error, search, loadMore };
}

function buildParams(condition, cursor) {
  const params = new URLSearchParams();
  if (condition.regionCode) params.set('regionCode', condition.regionCode);
  if (condition.dealYmFrom) params.set('dealYmFrom', condition.dealYmFrom);
  if (condition.dealYmTo) params.set('dealYmTo', condition.dealYmTo);
  if (condition.minArea) params.set('minArea', condition.minArea);
  if (condition.maxArea) params.set('maxArea', condition.maxArea);
  if (cursor) params.set('cursor', cursor);
  return params.toString();
}
