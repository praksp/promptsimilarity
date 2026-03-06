import { useState, useEffect } from 'react';
import { API } from './api';

const ORG_ID = 'default-org';

type Stats = {
  tokensSavedTotal: number;
  tokensSavedThisMonth: number;
  tokensSavedOrg: number;
  reuseCount: number;
  promptCount?: number;
  prompt_count?: number;
};

type Props = {
  apiOnline: boolean | null;
  refreshTrigger?: string;
};

export function RagStats({ apiOnline, refreshTrigger }: Props) {
  const [stats, setStats] = useState<Stats | null>(null);

  useEffect(() => {
    if (apiOnline === false) return;
    const url = `${API}/rag/stats?orgId=${encodeURIComponent(ORG_ID)}`;
    const fetchStats = () => {
      fetch(url)
        .then((r) => (r.ok ? r.json() : null))
        .then((data: Stats | null) => data && setStats(data))
        .catch(() => setStats(null));
    };
    fetchStats();
    // Poll so dashboard stays in sync with plugin (same backend, same metrics)
    const interval = setInterval(fetchStats, 30_000);
    return () => clearInterval(interval);
  }, [apiOnline, refreshTrigger]);

  if (stats === null) return null;

  const promptCount = typeof stats.promptCount === 'number' ? stats.promptCount : (typeof stats.prompt_count === 'number' ? stats.prompt_count : 0);

  return (
    <div className="rag-stats">
      <div className="rag-stats-title">RAG impact</div>
      <div className="rag-stats-grid">
        <div className="rag-stat-item">
          <span className="rag-stat-value">{promptCount.toLocaleString()}</span>
          <span className="rag-stat-label">Prompts (total)</span>
        </div>
        <div className="rag-stat-item">
          <span className="rag-stat-value">{stats.tokensSavedTotal.toLocaleString()}</span>
          <span className="rag-stat-label">Tokens saved (total)</span>
        </div>
        <div className="rag-stat-item">
          <span className="rag-stat-value">{stats.tokensSavedThisMonth.toLocaleString()}</span>
          <span className="rag-stat-label">This month</span>
        </div>
        <div className="rag-stat-item">
          <span className="rag-stat-value">{stats.reuseCount}</span>
          <span className="rag-stat-label">Reuses</span>
        </div>
      </div>
    </div>
  );
}
