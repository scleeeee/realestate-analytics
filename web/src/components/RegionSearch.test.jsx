import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import { RegionSearch } from './RegionSearch';

describe('RegionSearch', () => {
  it('calls onSearch with the selected condition', async () => {
    const onSearch = vi.fn();
    const user = userEvent.setup();
    render(<RegionSearch onSearch={onSearch} />);

    await user.selectOptions(screen.getByLabelText('지역'), '11110');
    await user.type(screen.getByPlaceholderText('from'), '202301');
    await user.type(screen.getByPlaceholderText('to'), '202312');
    await user.click(screen.getByRole('button', { name: '검색' }));

    expect(onSearch).toHaveBeenCalledWith({
      regionCode: '11110',
      dealYmFrom: '202301',
      dealYmTo: '202312',
      minArea: '',
      maxArea: '',
    });
  });
});
