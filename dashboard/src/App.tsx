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
      <header className="app-banner">
        <span className="app-banner-label">FirstInnings Technology Limited</span>
      </header>

      {apiOnline === false && (
        <div className="app-section">
          <div className="backend-offline">
            <strong>Backend not reachable</strong>
            <p>
              Start the stack with <code>./scripts/clean-build-run.sh</code> or <code>docker compose up -d</code>.
              The API gateway must be running at <code>http://localhost:8080</code>. If the dashboard is not
              run with <code>npm run dev</code>, set <code>VITE_API_BASE_URL=http://localhost:8080</code> before building.
            </p>
          </div>
        </div>
      )}

      <section className="app-section app-section-rag">
        <RagStats apiOnline={apiOnline} refreshTrigger={String(ragStatsTrigger)} />
      </section>

      <section className="app-section app-section-prompt">
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
      </section>

      {advancedMode && (
        <section className="app-section app-section-similarity">
          <SimilarityView
            currentUserId={userId}
            currentPromptId={lastPromptId}
            similarUsers={similarUsers}
          />
        </section>
      )}

      <section className="app-section app-section-prompts">
        <RecentPrompts apiOnline={apiOnline} refreshTrigger={String(ragStatsTrigger)} />
      </section>

      <section className="app-section app-section-find-similar">
        <FindSimilarPanel apiOnline={apiOnline} />
      </section>
    </div>
  );
}
