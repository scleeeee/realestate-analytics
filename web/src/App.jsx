import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { ErrorBoundary } from './components/ErrorBoundary';
import { SearchPage } from './pages/SearchPage';
import { RegionDetailPage } from './pages/RegionDetailPage';

export function App() {
  return (
    <ErrorBoundary>
      <BrowserRouter>
        <Routes>
          <Route path="/" element={<SearchPage />} />
          <Route path="/regions/:regionCode" element={<RegionDetailPage />} />
        </Routes>
      </BrowserRouter>
    </ErrorBoundary>
  );
}
