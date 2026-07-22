import { renderHook, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { useRegionStats } from './useRegionStats';

describe('useRegionStats', () => {
  beforeEach(() => {
    global.fetch = vi.fn();
  });

  it('loads monthly stats for the given range', async () => {
    global.fetch.mockResolvedValueOnce({
      ok: true,
      json: async () => ([{ regionCode: '11110', dealYm: 202301, count: 2, avgDealAmount: 90000 }]),
    });
    const { result } = renderHook(() => useRegionStats());

    await act(async () => {
      await result.current.loadRange('11110', 202301, 202312);
    });

    expect(result.current.monthlyStats).toEqual([
      { regionCode: '11110', dealYm: 202301, count: 2, avgDealAmount: 90000 },
    ]);
    expect(global.fetch).toHaveBeenCalledWith('/api/regions/11110/stats/range?from=202301&to=202312');
  });

  it('sets an error message when the request fails', async () => {
    global.fetch.mockResolvedValueOnce({ ok: false, status: 404 });
    const { result } = renderHook(() => useRegionStats());

    await act(async () => {
      await result.current.loadRange('11110', 202301, 202312);
    });

    expect(result.current.error).toContain('404');
  });
});
