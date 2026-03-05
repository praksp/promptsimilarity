import { useState, useEffect, useCallback } from 'react';
import { PromptInput } from './PromptInput';
import { PromptContext, type PromptContextData } from './PromptContext';
import { SimilarityView, type SimilarUser } from './SimilarityView';
import { FindSimilarPanel } from './FindSimilarPanel';
import { RecentPrompts } from './RecentPrompts';
import { LiveSimilarSuggestions } from './LiveSimilarSuggestions';
import { RagPanel } from './RagPanel';
import { RagStats } from './RagStats';
import './App.css';

import { API, HEALTH_URL } from './api';

const ORG_ID = 'default-org';

export default function App() {
  const [promptText, setPromptText] = useState('');
  const [userId, setUserId] = useState('alice');
  const [busy, setBusy] = useState(false);
  const [apiOnline, setApiOnline] = useState<boolean | null>(null);
  const [lastContext, setLastContext] = useState<PromptContextData | null>(null);
  const [similarUsers, setSimilarUsers] = useState<SimilarUser[]>([]);
  const [message, setMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);
  const [ragStatsTrigger, setRagStatsTrigger] = useState(0);

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

  const handleSubmit = async () => {
    if (!promptText.trim()) return;
    setBusy(true);
    setMessage(null);
    setSimilarUsers([]);
    try {
      const res = await fetch(`${API}/ingest`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          userId,
          orgId: ORG_ID,
          text: promptText.trim(),
          language: 'en',
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
        promptId: string;
        similarityDetected: boolean;
        similarUsers: SimilarUser[];
      };
      setLastContext({
        promptId: data.promptId,
        userId,
        orgId: ORG_ID,
        text: promptText.trim(),
        createdAt: Date.now(),
      });
      setSimilarUsers(data.similarUsers || []);
      setPromptText('');
      setMessage({
        type: 'success',
        text: data.similarityDetected
          ? `Prompt saved. Found ${data.similarUsers.length} similar prompt(s) from other users. When similarity is detected, a notification is sent to the notification service (see backend logs).`
          : 'Prompt saved. No similar prompts from other users yet.',
      });
    } catch (e) {
      setMessage({
        type: 'error',
        text: e instanceof Error ? e.message : 'Request failed',
      });
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="app-container">
      <header className="app-header">
        <h1>Prompt Similarity</h1>
        <p>
          Enter a prompt and see its context. When multiple users send similar prompts, you’ll see who else is working on related topics
          and how they are connected.
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

      <PromptInput
        value={promptText}
        onChange={setPromptText}
        userId={userId}
        onUserIdChange={setUserId}
        onSubmit={handleSubmit}
        busy={busy}
      />

      <LiveSimilarSuggestions text={promptText} userId={userId} apiOnline={apiOnline} />

      <RagStats apiOnline={apiOnline} refreshTrigger={String(ragStatsTrigger)} />
      <RagPanel
        userId={userId}
        apiOnline={apiOnline}
        onFeedbackSent={() => setRagStatsTrigger((t: number) => t + 1)}
      />

      {message && (
        <p className={`message ${message.type}`}>{message.text}</p>
      )}

      <div className="main-layout">
        <div className="main-column main-left">
          <PromptContext prompt={lastContext} />
          <FindSimilarPanel apiOnline={apiOnline} />
        </div>
        <div className="main-column main-right">
          <SimilarityView
            currentUserId={userId}
            currentPromptId={lastContext?.promptId ?? ''}
            similarUsers={similarUsers}
            loading={busy}
          />
          <RecentPrompts apiOnline={apiOnline} refreshTrigger={lastContext?.promptId} />
        </div>
      </div>
    </div>
  );
}
