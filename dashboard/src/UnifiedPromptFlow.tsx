import { useState } from 'react';
import { API } from './api';

const ORG_ID = 'default-org';

export type SimilarMatch = {
  promptId: string;
  responseId: string;
  promptPreview: string;
  responseText: string;
  similarityScore: number;
  tokensUsed: number;
};

export type IngestSimilarUser = {
  userId: string;
  promptId: string;
  similarityScore: number;
  textPreview?: string;
};

type Props = {
  userId: string;
  apiOnline: boolean | null;
  onRagImpact?: () => void;
  onIngestResult?: (data: { promptId: string; similarUsers: IngestSimilarUser[] }) => void;
};

export function UnifiedPromptFlow({ userId, apiOnline, onRagImpact, onIngestResult }: Props) {
  const [promptText, setPromptText] = useState('');
  const [user, setUser] = useState(userId);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);
  const [similarMatches, setSimilarMatches] = useState<SimilarMatch[] | null>(null);
  const [currentPrompt, setCurrentPrompt] = useState('');
  const [llmResponse, setLlmResponse] = useState<{
    responseText: string;
    responseId: string;
    promptId: string;
    tokensUsed: number;
  } | null>(null);

  const handleSubmit = async () => {
    if (!promptText.trim() || apiOnline === false) return;
    setLoading(true);
    setError(null);
    setMessage(null);
    setSimilarMatches(null);
    setLlmResponse(null);
    setCurrentPrompt(promptText.trim());
    const trimmed = promptText.trim();
    try {
      // 1) Ingest: store prompt in vector/graph and get similar users (restores similarity-between-users)
      const ingestRes = await fetch(`${API}/ingest`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          userId: user,
          orgId: ORG_ID,
          text: trimmed,
          language: 'en',
        }),
      });
      const ingestText = await ingestRes.text();
      if (ingestRes.ok) {
        try {
          const ingestData = JSON.parse(ingestText) as {
            promptId: string;
            similarityDetected: boolean;
            similarUsers: Array<{ userId: string; promptId: string; similarityScore: number; textPreview?: string }>;
          };
          onIngestResult?.({
            promptId: ingestData.promptId,
            similarUsers: ingestData.similarUsers ?? [],
          });
        } catch {
          onIngestResult?.({ promptId: '', similarUsers: [] });
        }
      }

      // 2) RAG: find similar prompts that have LLM responses (for "Use this" / "Get from LLM")
      const ragRes = await fetch(`${API}/rag/similar-responses`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ prompt: trimmed, userId: user, orgId: ORG_ID }),
      });
      const ragText = await ragRes.text();
      if (!ragRes.ok) {
        try {
          const err = JSON.parse(ragText) as { message?: string };
          throw new Error(err.message ?? ragText);
        } catch (e) {
          if (e instanceof Error && e.message !== ragText) throw e;
          throw new Error(ragText || `Request failed (${ragRes.status})`);
        }
      }
      const ragData = JSON.parse(ragText) as SimilarMatch[];
      setSimilarMatches(Array.isArray(ragData) ? ragData : []);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Request failed');
      setSimilarMatches([]);
    } finally {
      setLoading(false);
    }
  };

  const handleUseThis = async (match: SimilarMatch) => {
    setError(null);
    try {
      await fetch(`${API}/rag/feedback`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          responseId: match.responseId,
          satisfied: true,
          orgId: ORG_ID,
        }),
      });
      await fetch(`${API}/rag/record-satisfied`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          promptText: currentPrompt,
          userId: user,
          orgId: ORG_ID,
          similarToPromptId: match.promptId,
          similarityScore: match.similarityScore,
        }),
      });
      setMessage({
        type: 'success',
        text: `Using existing answer. Saved ~${match.tokensUsed} tokens. Prompt and similarity recorded.`,
      });
      setSimilarMatches(null);
      setCurrentPrompt('');
      setPromptText('');
      onRagImpact?.();
    } catch {
      setError('Failed to record. Please try again.');
    }
  };

  const handleGetNewFromLlm = async () => {
    if (!currentPrompt || apiOnline === false) return;
    setLoading(true);
    setError(null);
    setLlmResponse(null);
    try {
      const similarMatchesPayload = (similarMatches ?? []).map((m) => ({
        promptId: m.promptId,
        score: m.similarityScore,
      }));
      const res = await fetch(`${API}/rag/ask-llm`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          prompt: currentPrompt,
          userId: user,
          orgId: ORG_ID,
          similarMatches: similarMatchesPayload,
        }),
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
      const data = JSON.parse(text) as {
        responseText: string;
        responseId: string;
        promptId: string;
        tokensUsed: number;
      };
      setLlmResponse(data);
      setSimilarMatches(null);
      setPromptText('');
      setCurrentPrompt('');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Request failed');
    } finally {
      setLoading(false);
    }
  };

  return (
    <section className="unified-prompt-section">
      <h2 className="section-title">Your prompt</h2>
      <p className="unified-prompt-hint">
        Enter your question. We’ll find similar prompts and their LLM answers first. Choose one or get a new answer from the LLM.
      </p>
      <div className="unified-prompt-row">
        <textarea
          className="prompt-textarea unified-textarea"
          placeholder="Enter your question or prompt…"
          value={promptText}
          onChange={(e) => setPromptText(e.target.value)}
          rows={4}
          disabled={apiOnline === false}
        />
        <div className="unified-actions">
          <label className="user-label">
            <span>User</span>
            <input
              type="text"
              className="user-input"
              value={user}
              onChange={(e) => setUser(e.target.value)}
              placeholder="e.g. alice"
              disabled={loading}
            />
          </label>
          <button
            type="button"
            className="submit-btn"
            onClick={handleSubmit}
            disabled={loading || !promptText.trim() || apiOnline === false}
          >
            {loading && similarMatches === null ? 'Searching…' : 'Submit'}
          </button>
        </div>
      </div>

      {error && <p className="message error">{error}</p>}
      {message && <p className={`message ${message.type}`}>{message.text}</p>}

      {/* Similar prompts + responses: choose one or get new from LLM */}
      {similarMatches !== null && !loading && (
        <div className="unified-result">
          {similarMatches.length > 0 ? (
            <>
              <h3 className="unified-result-title">Similar questions and answers found</h3>
              <p className="unified-result-desc">Choose an existing answer or get a new one from the LLM.</p>
              <div className="unified-similar-cards">
                {similarMatches.map((m) => (
                  <div key={m.responseId} className="unified-similar-card">
                    <div className="unified-similar-prompt">{m.promptPreview || '(No preview)'}</div>
                    <div className="unified-similar-response">{m.responseText.length > 300 ? m.responseText.slice(0, 300) + '…' : m.responseText}</div>
                    <div className="unified-similar-meta">
                      Similarity: {Math.round(m.similarityScore * 100)}% · ~{m.tokensUsed} tokens
                    </div>
                    <button
                      type="button"
                      className="rag-feedback-btn rag-feedback-yes"
                      onClick={() => handleUseThis(m)}
                    >
                      Use this answer
                    </button>
                  </div>
                ))}
              </div>
            </>
          ) : (
            <p className="unified-no-similar">No similar questions with answers found.</p>
          )}
          <div className="unified-llm-row">
            <button
              type="button"
              className="submit-btn unified-llm-btn"
              onClick={handleGetNewFromLlm}
              disabled={loading || !currentPrompt}
            >
              {loading ? 'Calling LLM…' : 'Get new answer from LLM'}
            </button>
          </div>
        </div>
      )}

      {/* New LLM response */}
      {llmResponse && (
        <div className="unified-llm-response">
          <h3 className="unified-result-title">Answer from LLM</h3>
          <div className="rag-response-text">{llmResponse.responseText || '(No text)'}</div>
          <div className="rag-response-meta">
            <span className="rag-tokens">~{llmResponse.tokensUsed} tokens</span>
          </div>
        </div>
      )}
    </section>
  );
}
