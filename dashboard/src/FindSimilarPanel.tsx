import { useState, type FC } from 'react';
import { API } from './api';

const ORG_ID = 'default-org';

export type SimilarResult = { promptId: string; userId: string; score: number; textPreview: string };

type Props = { apiOnline: boolean | null };

export const FindSimilarPanel: FC<Props> = ({ apiOnline }) => {
  const [query, setQuery] = useState('');
  const [busy, setBusy] = useState(false);
  const [results, setResults] = useState<SimilarResult[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const handleSearch = async () => {
    if (!query.trim()) return;
    setBusy(true);
    setError(null);
    setResults(null);
    try {
      const params = new URLSearchParams({
        text: query.trim(),
        orgId: ORG_ID,
        userId: 'dashboard',
        topK: '10',
        minScore: '0.5',
      });
      const res = await fetch(`${API}/similar?${params}`);
      const text = await res.text();
      if (!res.ok) {
        try {
          const err = JSON.parse(text) as { message?: string };
          throw new Error((err.message ?? text) || `Search failed (${res.status})`);
        } catch (e) {
          if (e instanceof Error && e.message !== text) throw e;
          throw new Error(text || `Search failed (${res.status})`);
        }
      }
      const data = JSON.parse(text) as SimilarResult[];
      setResults(data);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Search failed');
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="find-similar-section">
      <h2 className="section-title">Find similar prompts (search)</h2>
      <p style={{ color: 'var(--muted)', fontSize: '0.9rem', margin: '0 0 0.5rem 0' }}>
        Enter text to find prompts from other users that are similar.
      </p>
      <div className="find-similar-row">
        <input
          type="text"
          className="find-similar-input"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && handleSearch()}
          placeholder="e.g. JWT authentication Spring Boot"
          disabled={apiOnline === false || busy}
        />
        <button
          type="button"
          className="find-similar-btn"
          onClick={handleSearch}
          disabled={apiOnline === false || busy || !query.trim()}
        >
          {busy ? 'Searching…' : 'Search'}
        </button>
      </div>
      {error && <p className="message error">{error}</p>}
      {results && (
        <ul className="similarity-list" style={{ marginTop: '1rem' }}>
          {results.length === 0 ? (
            <li style={{ color: 'var(--muted)', fontSize: '0.9rem' }}>No similar prompts found.</li>
          ) : (
            results.map((r) => (
              <li key={r.promptId} className="similarity-card">
                <div className="similarity-card-header">
                  <span className="similarity-user">{r.userId}</span>
                  <span className="similarity-score">{(r.score * 100).toFixed(1)}%</span>
                </div>
                <div className="similarity-score-bar">
                  <div className="similarity-score-fill" style={{ width: `${r.score * 100}%` }} />
                </div>
                <div className="similarity-prompt-id">Prompt ID: {r.promptId.slice(0, 12)}…</div>
                {r.textPreview && (
                  <div style={{ fontSize: '0.85rem', color: 'var(--muted)', marginTop: '0.35rem' }}>
                    {r.textPreview.slice(0, 120)}{r.textPreview.length > 120 ? '…' : ''}
                  </div>
                )}
              </li>
            ))
          )}
        </ul>
      )}
    </section>
  );
};
