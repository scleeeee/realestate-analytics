import { renderHook, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { useTransactionSearch } from './useTransactionSearch';

describe('useTransactionSearch', () => {
  beforeEach(() => {
    global.fetch = vi.fn();
  });

  it('loads search results into items', async () => {
    global.fetch.mockResolvedValueOnce({
      ok: true,
      json: async () => ({ items: [{ id: 1, aptName: 'A' }], nextCursor: 'abc' }),
    });
    const { result } = renderHook(() => useTransactionSearch());

    await act(async () => {
      await result.current.search({ regionCode: '11110' });
    });

    expect(result.current.items).toEqual([{ id: 1, aptName: 'A' }]);
    expect(result.current.nextCursor).toBe('abc');
    expect(global.fetch).toHaveBeenCalledWith(expect.stringContaining('regionCode=11110'));
  });

  it('appends results on loadMore using the cursor', async () => {
    global.fetch
      .mockResolvedValueOnce({ ok: true, json: async () => ({ items: [{ id: 1 }], nextCursor: 'cursor1' }) })
      .mockResolvedValueOnce({ ok: true, json: async () => ({ items: [{ id: 2 }], nextCursor: null }) });
    const { result } = renderHook(() => useTransactionSearch());

    await act(async () => {
      await result.current.search({ regionCode: '11110' });
    });
    await act(async () => {
      await result.current.loadMore({ regionCode: '11110' });
    });

    expect(result.current.items).toEqual([{ id: 1 }, { id: 2 }]);
    expect(result.current.nextCursor).toBeNull();
    expect(global.fetch).toHaveBeenLastCalledWith(expect.stringContaining('cursor=cursor1'));
  });

  it('sets an error message when the request fails', async () => {
    global.fetch.mockResolvedValueOnce({ ok: false, status: 500 });
    const { result } = renderHook(() => useTransactionSearch());

    await act(async () => {
      await result.current.search({ regionCode: '11110' });
    });

    expect(result.current.error).toContain('500');
  });
});
