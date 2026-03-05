import { useState, useEffect, useCallback } from 'react';
import { UnifiedPromptFlow } from './UnifiedPromptFlow';
import { FindSimilarPanel } from './FindSimilarPanel';
import { RecentPrompts } from './RecentPrompts';
import { RagStats } from './RagStats';
import { SimilarityView, type SimilarUser } from './SimilarityView';
import './App.css';

import { HEALTH_URL } from './api';

export default function App() {
  const [userId] = useState('alice');
  const [apiOnline, setApiOnline] = useState<boolean | null>(null);
  const [ragStatsTrigger, setRagStatsTrigger] = useState(0);
  const [advancedMode, setAdvancedMode] = useState(true);
  const [similarUsers, setSimilarUsers] = useState<SimilarUser[]>([]);
  const [lastPromptId, setLastPromptId] = useState('');

  const checkApi = useCallback(async () => {
    try {
      const res = await fetch(HEALTH_URL, { method: 'GET' });
      setApiOnline(res.ok);
    } catch {
      setApiOnline(false);
    }
  }, []);

  useEffect(() => {
    checkApi();
    const t = setInterval(checkApi, 15000);
    return () => clearInterval(t);
  }, [checkApi]);

  return (
    <div className="app-container">
      <header className="app-header">
        <h1>Prompt Similarity</h1>
        <p>
          Enter your prompt. We find similar questions and their LLM answers first. If one satisfies you, we count it as RAG impact (tokens saved). Otherwise we call the LLM and store the result.
        </p>
      </header>

      {apiOnline === false && (
        <div className="backend-offline">
          <strong>Backend not reachable</strong>
          <p>
            Start the stack with <code>./scripts/clean-build-run.sh</code> or <code>docker compose up -d</code>.
            The API gateway must be running at <code>http://localhost:8080</code>. If the dashboard is not
            run with <code>npm run dev</code>, set <code>VITE_API_BASE_URL=http://localhost:8080</code> before building.
          </p>
        </div>
      )}

      <div className="advanced-mode-row">
        <label className="advanced-mode-toggle">
          <input
            type="checkbox"
            checked={advancedMode}
            onChange={(e) => setAdvancedMode(e.target.checked)}
          />
          <span>Advanced: show similarity between prompts and users</span>
        </label>
      </div>

      <UnifiedPromptFlow
        userId={userId}
        apiOnline={apiOnline}
        onRagImpact={() => setRagStatsTrigger((t: number) => t + 1)}
        onIngestResult={(data) => {
          setLastPromptId(data.promptId);
          setSimilarUsers(
            (data.similarUsers ?? []).map((u) => ({
              userId: u.userId,
              promptId: u.promptId,
              similarityScore: u.similarityScore,
              textPreview: u.textPreview,
            }))
          );
        }}
      />

      <RagStats apiOnline={apiOnline} refreshTrigger={String(ragStatsTrigger)} />

      <div className="main-layout">
        <div className="main-column main-left">
          <FindSimilarPanel apiOnline={apiOnline} />
        </div>
        <div className="main-column main-right">
          {advancedMode && (
            <SimilarityView
              currentUserId={userId}
              currentPromptId={lastPromptId}
              similarUsers={similarUsers}
            />
          )}
          <RecentPrompts apiOnline={apiOnline} refreshTrigger={String(ragStatsTrigger)} />
        </div>
      </div>
    </div>
  );
}
