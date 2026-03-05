import { type FC } from 'react';

export type PromptContextData = {
  promptId: string;
  userId: string;
  orgId: string;
  text: string;
  createdAt: number;
};

type Props = {
  prompt: PromptContextData | null;
};

export const PromptContext: FC<Props> = ({ prompt }) => {
  if (!prompt) return null;

  const date = new Date(prompt.createdAt);
  const timeStr = isNaN(date.getTime()) ? '—' : date.toLocaleString();

  return (
    <section className="context-section">
      <h2 className="section-title">Prompt context</h2>
      <div className="context-card">
        <div className="context-meta">
          <span className="context-id" title={prompt.promptId}>
            ID: {prompt.promptId.slice(0, 8)}…
          </span>
          <span className="context-user">User: {prompt.userId}</span>
          <span className="context-time">{timeStr}</span>
        </div>
        <div className="context-text">{prompt.text}</div>
      </div>
    </section>
  );
};
