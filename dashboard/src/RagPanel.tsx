import { useState } from 'react';
import { API } from './api';

const ORG_ID = 'default-org';

export type RagAskResponse = {
  responseText: string;
  responseId: string;
  promptId: string;
  tokensUsed: number;
  similarityScore: number;
  fromCache: boolean;
  askSatisfaction: boolean;
};

type Props = {
  userId: string;
  apiOnline: boolean | null;
  onFeedbackSent?: () => void;
};

export function RagPanel({ userId, apiOnline, onFeedbackSent }: Props) {
  const [prompt, setPrompt] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lastResponse, setLastResponse] = useState<RagAskResponse | null>(null);
  const [feedbackSent, setFeedbackSent] = useState(false);

  const handleAsk = async () => {
    if (!prompt.trim() || apiOnline === false) return;
    setLoading(true);
    setError(null);
    setLastResponse(null);
    setFeedbackSent(false);
    try {
      const res = await fetch(`${API}/rag/ask`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ prompt: prompt.trim(), userId, orgId: ORG_ID }),
      });
      const text = await res.text();
      if (!res.ok) {
        try {
          const err = JSON.parse(text) as { message?: string };
          throw new Error(err.message ?? text);
        } catch (e) {
          if (e instanceof Error && e.message !== text) throw e;
          throw new Error(text || `Request failed (${res.status})`);
        }
      }
      const data = JSON.parse(text) as RagAskResponse;
      setLastResponse(data);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Request failed');
    } finally {
      setLoading(false);
    }
  };

  const sendFeedback = async (satisfied: boolean) => {
    if (!lastResponse?.responseId) return;
    try {
      await fetch(`${API}/rag/feedback`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          responseId: lastResponse.responseId,
          satisfied,
          orgId: ORG_ID,
        }),
      });
      setFeedbackSent(true);
      onFeedbackSent?.();
    } catch {
      setError('Failed to send feedback');
    }
  };

  return (
    <section className="rag-section">
      <h2 className="section-title">Ask the LLM (RAG) — sends to Llama</h2>
      <p className="rag-desc">
        Ask a question here to get an answer from the LLM (Llama). If a similar question was answered before, you’ll see that answer and save tokens.
      </p>
      <div className="rag-input-row">
        <textarea
          className="rag-textarea"
          placeholder="Type your question here to send to Llama… e.g. How do I optimize energy usage at home?"
          value={prompt}
          onChange={(e) => setPrompt(e.target.value)}
          rows={3}
          disabled={apiOnline === false}
        />
        <button
          type="button"
          className="submit-btn rag-ask-btn"
          onClick={handleAsk}
          disabled={loading || !prompt.trim() || apiOnline === false}
        >
          {loading ? 'Asking…' : 'Ask'}
        </button>
      </div>
      {error && <p className="message error">{error}</p>}
      {lastResponse && (
        <div className="rag-response-card">
          <div className="rag-response-meta">
            {lastResponse.fromCache ? (
              <span className="rag-badge rag-badge-cache">From similar prompt</span>
            ) : (
              <span className="rag-badge rag-badge-new">New answer</span>
            )}
            <span className="rag-tokens">~{lastResponse.tokensUsed} tokens</span>
            {lastResponse.fromCache && (
              <span className="rag-similarity">
                Similarity: {Math.round(lastResponse.similarityScore * 100)}%
              </span>
            )}
          </div>
          <div className="rag-response-text">{lastResponse.responseText || '(No text)'}</div>
          {lastResponse.askSatisfaction && !feedbackSent && (
            <div className="rag-feedback">
              <span className="rag-feedback-label">Did this help?</span>
              <div className="rag-feedback-buttons">
                <button
                  type="button"
                  className="rag-feedback-btn rag-feedback-yes"
                  onClick={() => sendFeedback(true)}
                >
                  Yes
                </button>
                <button
                  type="button"
                  className="rag-feedback-btn rag-feedback-no"
                  onClick={() => sendFeedback(false)}
                >
                  No
                </button>
              </div>
            </div>
          )}
          {feedbackSent && (
            <p className="rag-feedback-sent">Thanks for your feedback.</p>
          )}
        </div>
      )}
    </section>
  );
}
