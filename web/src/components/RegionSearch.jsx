import { useState } from 'react';
import regionCodes from '../data/region-codes.json';

export function RegionSearch({ onSearch }) {
  const [regionCode, setRegionCode] = useState('');
  const [dealYmFrom, setDealYmFrom] = useState('');
  const [dealYmTo, setDealYmTo] = useState('');
  const [minArea, setMinArea] = useState('');
  const [maxArea, setMaxArea] = useState('');

  function handleSubmit(e) {
    e.preventDefault();
    onSearch({ regionCode, dealYmFrom, dealYmTo, minArea, maxArea });
  }

  return (
    <form onSubmit={handleSubmit}>
      <label htmlFor="regionCode">지역</label>
      <select id="regionCode" value={regionCode} onChange={(e) => setRegionCode(e.target.value)} required>
        <option value="">지역 선택</option>
        {regionCodes.map((region) => (
          <option key={region.code} value={region.code}>{region.name}</option>
        ))}
      </select>

      <input value={dealYmFrom} onChange={(e) => setDealYmFrom(e.target.value)} placeholder="from" aria-label="기간 시작(YYYYMM)" />
      <input value={dealYmTo} onChange={(e) => setDealYmTo(e.target.value)} placeholder="to" aria-label="기간 종료(YYYYMM)" />
      <input value={minArea} onChange={(e) => setMinArea(e.target.value)} placeholder="최소 평형(㎡)" aria-label="최소 평형" />
      <input value={maxArea} onChange={(e) => setMaxArea(e.target.value)} placeholder="최대 평형(㎡)" aria-label="최대 평형" />

      <button type="submit">검색</button>
    </form>
  );
}
