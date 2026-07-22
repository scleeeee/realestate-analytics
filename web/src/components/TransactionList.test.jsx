import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import { TransactionList } from './TransactionList';

const sampleItems = [
  { id: 1, aptName: '테스트아파트', exclusiveArea: 84.9, dealAmount: 90000, dealYm: 202307 },
];

describe('TransactionList', () => {
  it('renders each transaction', () => {
    render(<TransactionList items={sampleItems} hasMore={false} loading={false} onLoadMore={vi.fn()} />);

    expect(screen.getByText(/테스트아파트/)).toBeInTheDocument();
  });

  it('shows a load-more button only when hasMore is true, and calls onLoadMore when clicked', async () => {
    const onLoadMore = vi.fn();
    const user = userEvent.setup();
    render(<TransactionList items={sampleItems} hasMore={true} loading={false} onLoadMore={onLoadMore} />);

    const button = screen.getByRole('button', { name: '더보기' });
    await user.click(button);

    expect(onLoadMore).toHaveBeenCalledTimes(1);
  });

  it('does not render a load-more button when hasMore is false', () => {
    render(<TransactionList items={sampleItems} hasMore={false} loading={false} onLoadMore={vi.fn()} />);

    expect(screen.queryByRole('button', { name: '더보기' })).not.toBeInTheDocument();
  });
});
