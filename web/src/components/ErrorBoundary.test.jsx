import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { ErrorBoundary } from './ErrorBoundary';

function Bomb() {
  throw new Error('boom');
}

describe('ErrorBoundary', () => {
  it('renders children when there is no error', () => {
    render(
      <ErrorBoundary>
        <p>all good</p>
      </ErrorBoundary>
    );

    expect(screen.getByText('all good')).toBeInTheDocument();
  });

  it('renders a fallback message instead of crashing when a child throws', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});

    render(
      <ErrorBoundary>
        <Bomb />
      </ErrorBoundary>
    );

    expect(screen.getByText('문제가 발생했습니다. 페이지를 새로고침해 주세요.')).toBeInTheDocument();

    console.error.mockRestore();
  });
});
