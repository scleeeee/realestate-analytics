export function TransactionList({ items, hasMore, loading, onLoadMore }) {
  return (
    <div>
      <ul>
        {items.map((tx) => (
          <li key={tx.id}>
            {tx.aptName} · {tx.exclusiveArea}㎡ · {tx.dealAmount.toLocaleString()}만원 · {tx.dealYm}
          </li>
        ))}
      </ul>
      {hasMore && (
        <button onClick={onLoadMore} disabled={loading}>더보기</button>
      )}
    </div>
  );
}
