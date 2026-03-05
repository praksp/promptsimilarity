import { useState, useEffect, useCallback, type FC } from 'react';
import { API } from './api';

export type PromptEntry = {
  promptId: string;
  userId: string;
  orgId: string;
  text: string;
  createdAt: number;
};

type Props = {
  apiOnline: boolean | null;
  refreshTrigger?: string | number;
};

const PREVIEW_LEN = 80;
const POLL_INTERVAL_MS = 8000;

function formatTime(ms: number): string {
  const d = new Date(ms);
  const now = Date.now();
  const diff = now - ms;
  if (diff < 60000) return 'Just now';
  if (diff < 3600000) return `${Math.floor(diff / 60000)}m ago`;
  if (diff < 86400000) return `${Math.floor(diff / 3600000)}h ago`;
  return d.toLocaleString();
}

export const RecentPrompts: FC<Props> = ({ apiOnline, refreshTrigger }) => {
  const [prompts, setPrompts] = useState<PromptEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [enabled, setEnabled] = useState(false);

  const fetchList = useCallback(async () => {
    if (apiOnline === false || !enabled) return;
    setError(null);
    setLoading(true);
    try {
      const res = await fetch(`${API}/list`);
      const text = await res.text();
      if (!res.ok) {
        setError(text || `Failed to load (${res.status})`);
        setPrompts([]);
        return;
      }
      const data = JSON.parse(text) as PromptEntry[];
      setPrompts(Array.isArray(data) ? data : []);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load prompts');
      setPrompts([]);
    } finally {
      setLoading(false);
    }
  }, [apiOnline, enabled]);

  useEffect(() => {
    if (!enabled) return;
    fetchList();
  }, [fetchList, refreshTrigger, enabled]);

  useEffect(() => {
    if (apiOnline !== true || !enabled) return;
    const t = setInterval(fetchList, POLL_INTERVAL_MS);
    return () => clearInterval(t);
  }, [apiOnline, enabled, fetchList]);

  return (
    <section className="recent-prompts-section">
      <h2 className="section-title">All prompts in the system</h2>
      <p className="recent-prompts-desc">
        Every prompt entered is stored here (user, time, text). This confirms the backend is saving prompts.
      </p>
      <div className="recent-prompts-toggle">
        <label>
          <input
            type="checkbox"
            checked={enabled}
            onChange={(e) => setEnabled(e.target.checked)}
            disabled={apiOnline === false}
          />
          <span>Load prompts on demand</span>
        </label>
      </div>
      {apiOnline === false && (
        <div className="recent-prompts-empty">Backend offline. Start the stack to see prompts.</div>
      )}
      {apiOnline !== false && enabled && loading && prompts.length === 0 && (
        <div className="recent-prompts-loading">Loading…</div>
      )}
      {apiOnline !== false && enabled && error && <p className="message error">{error}</p>}
      {apiOnline !== false && enabled && !loading && !error && prompts.length === 0 && (
        <div className="recent-prompts-empty">No prompts yet. Submit a prompt above.</div>
      )}
      {enabled && prompts.length > 0 && (
        <ul className="recent-prompts-list">
          {prompts.map((p) => (
            <li key={p.promptId} className="recent-prompt-card">
              <div className="recent-prompt-meta">
                <span className="recent-prompt-user">{p.userId}</span>
                <span className="recent-prompt-time" title={new Date(p.createdAt).toISOString()}>
                  {formatTime(p.createdAt)}
                </span>
              </div>
              <div className="recent-prompt-text" title={p.text}>
                {p.text.length > PREVIEW_LEN ? p.text.slice(0, PREVIEW_LEN) + '…' : p.text}
              </div>
              <div className="recent-prompt-id">ID: {p.promptId.slice(0, 8)}…</div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
};
