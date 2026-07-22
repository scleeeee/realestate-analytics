import { useEffect } from 'react';
import { useParams, Link } from 'react-router-dom';
import { PriceTrendChart } from '../components/PriceTrendChart';
import { useRegionStats } from '../hooks/useRegionStats';

export function RegionDetailPage() {
  const { regionCode } = useParams();
  const { monthlyStats, loading, error, loadRange } = useRegionStats();

  useEffect(() => {
    const to = currentYearMonth();
    const from = to - 100; // YYYYMM integer arithmetic: -100 steps back exactly one year
    loadRange(regionCode, from, to);
  }, [regionCode, loadRange]);

  return (
    <div>
      <p><Link to="/">← 검색으로</Link></p>
      <h1>{regionCode} 시세추이</h1>
      {loading && <p>로딩 중...</p>}
      {error && <p role="alert">{error}</p>}
      {!loading && !error && <PriceTrendChart monthlyStats={monthlyStats} />}
    </div>
  );
}

function currentYearMonth() {
  const now = new Date();
  return now.getFullYear() * 100 + (now.getMonth() + 1);
}
