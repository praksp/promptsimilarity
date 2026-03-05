import { useEffect, useState, useCallback, type FC } from 'react';
import { API } from './api';
import type { SimilarResult } from './FindSimilarPanel';

const ORG_ID = 'default-org';
const DEBOUNCE_MS = 400;
const MIN_CHARS = 8;

type Props = {
  text: string;
  userId: string;
  apiOnline: boolean | null;
};

export const LiveSimilarSuggestions: FC<Props> = ({ text, userId, apiOnline }) => {
  const [results, setResults] = useState<SimilarResult[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const runSearch = useCallback(
    async (q: string) => {
      if (apiOnline === false) return;
      if (!q.trim() || q.trim().length < MIN_CHARS) {
        setResults([]);
        setError(null);
        setLoading(false);
        return;
      }
      setLoading(true);
      setError(null);
      try {
        const params = new URLSearchParams({
          text: q.trim(),
          orgId: ORG_ID,
          userId,
          topK: '10',
          // Lower threshold for "as you type" discovery
          minScore: '0.4',
        });
        const res = await fetch(`${API}/live-similar?${params}`);
        const textBody = await res.text();
        if (!res.ok) {
          try {
            const err = JSON.parse(textBody) as { message?: string };
            throw new Error((err.message ?? textBody) || `Search failed (${res.status})`);
          } catch (e) {
            if (e instanceof Error && e.message !== textBody) throw e;
            throw new Error(textBody || `Search failed (${res.status})`);
          }
        }
        const data = JSON.parse(textBody) as SimilarResult[];
        setResults(Array.isArray(data) ? data : []);
      } catch (e) {
        setError(e instanceof Error ? e.message : 'Similarity lookup failed');
        setResults([]);
      } finally {
        setLoading(false);
      }
    },
    [apiOnline, userId],
  );

  useEffect(() => {
    if (apiOnline === false) return;
    const handle = setTimeout(() => {
      void runSearch(text);
    }, DEBOUNCE_MS);
    return () => clearTimeout(handle);
  }, [text, apiOnline, runSearch]);

  if (apiOnline === false) {
    return null;
  }

  const hasQuery = text.trim().length >= MIN_CHARS;

  return (
    <section className="live-similar-section">
      <h2 className="section-title">Similar prompts while you type</h2>
      <p className="live-similar-desc">
        As you type, prompts from other users with a similarity score above 40% appear here before you submit.
      </p>
      {!hasQuery && (
        <div className="live-similar-empty">Start typing (at least {MIN_CHARS} characters) to see similar prompts.</div>
      )}
      {hasQuery && loading && (
        <div className="live-similar-empty">Checking for similar prompts…</div>
      )}
      {hasQuery && !loading && error && <p className="message error">{error}</p>}
      {hasQuery && !loading && !error && results.length === 0 && (
        <div className="live-similar-empty">No similar prompts yet at 40%+ similarity.</div>
      )}
      {results.length > 0 && (
        <ul className="similarity-list live-similar-list">
          {results.map((r) => (
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
                <div className="similarity-prompt-text" title={r.textPreview}>
                  {r.textPreview.length > 150 ? r.textPreview.slice(0, 150) + '…' : r.textPreview}
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

