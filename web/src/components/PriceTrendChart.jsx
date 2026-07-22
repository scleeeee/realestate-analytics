import { Line } from 'react-chartjs-2';
import {
  Chart as ChartJS,
  CategoryScale,
  LinearScale,
  PointElement,
  LineElement,
  Tooltip,
} from 'chart.js';

ChartJS.register(CategoryScale, LinearScale, PointElement, LineElement, Tooltip);

export function PriceTrendChart({ monthlyStats }) {
  if (monthlyStats.length === 0) {
    return <p>표시할 데이터가 없습니다.</p>;
  }

  const data = {
    labels: monthlyStats.map((s) => String(s.dealYm)),
    datasets: [
      {
        label: '평균 거래가(만원)',
        data: monthlyStats.map((s) => s.avgDealAmount),
      },
    ],
  };

  return <Line data={data} />;
}
