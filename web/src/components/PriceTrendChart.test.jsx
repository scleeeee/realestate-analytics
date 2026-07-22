import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';

vi.mock('react-chartjs-2', () => ({
  Line: () => <div data-testid="mock-chart" />,
}));

import { PriceTrendChart } from './PriceTrendChart';

describe('PriceTrendChart', () => {
  it('shows a message when there is no data', () => {
    render(<PriceTrendChart monthlyStats={[]} />);

    expect(screen.getByText('표시할 데이터가 없습니다.')).toBeInTheDocument();
  });

  it('renders the chart when data is present', () => {
    render(<PriceTrendChart monthlyStats={[{ dealYm: 202301, count: 1, avgDealAmount: 90000 }]} />);

    expect(screen.getByTestId('mock-chart')).toBeInTheDocument();
  });
});
