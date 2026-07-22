import { useState } from 'react';
import { Link } from 'react-router-dom';
import { RegionSearch } from '../components/RegionSearch';
import { TransactionList } from '../components/TransactionList';
import { useTransactionSearch } from '../hooks/useTransactionSearch';

export function SearchPage() {
  const [condition, setCondition] = useState(null);
  const { items, nextCursor, loading, error, search, loadMore } = useTransactionSearch();

  function handleSearch(newCondition) {
    setCondition(newCondition);
    search(newCondition);
  }

  return (
    <div>
      <h1>실거래가 검색</h1>
      <RegionSearch onSearch={handleSearch} />
      {condition?.regionCode && (
        <p><Link to={`/regions/${condition.regionCode}`}>이 지역 시세추이 보기</Link></p>
      )}
      {error && <p role="alert">{error}</p>}
      <TransactionList
        items={items}
        hasMore={Boolean(nextCursor)}
        loading={loading}
        onLoadMore={() => loadMore(condition)}
      />
    </div>
  );
}
