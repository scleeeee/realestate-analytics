import { useCallback, useState } from 'react';
import { apiGet } from '../api/client';

export function useRegionStats() {
  const [monthlyStats, setMonthlyStats] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const loadRange = useCallback(async (regionCode, from, to) => {
    setLoading(true);
    setError(null);
    try {
      const data = await apiGet(`/api/regions/${regionCode}/stats/range?from=${from}&to=${to}`);
      setMonthlyStats(data);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, []);

  return { monthlyStats, loading, error, loadRange };
}
