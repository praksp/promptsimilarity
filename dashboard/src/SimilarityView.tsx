import { type FC } from 'react';

export type SimilarUser = {
  userId: string;
  promptId: string;
  similarityScore: number;
  textPreview?: string;
};

type Props = {
  currentUserId: string;
  currentPromptId: string;
  similarUsers: SimilarUser[];
  loading?: boolean;
};

export const SimilarityView: FC<Props> = ({
  currentUserId,
  currentPromptId,
  similarUsers,
  loading,
}) => {
  if (loading) {
    return (
      <section className="similarity-section">
        <h2 className="section-title">Similarity with other users</h2>
        <div className="similarity-loading">Checking for similar prompts…</div>
      </section>
    );
  }

  if (similarUsers.length === 0) {
    return (
      <section className="similarity-section">
        <h2 className="section-title">Similarity with other users</h2>
        <div className="similarity-empty">
          No similar prompts from other users yet.
        </div>
        <p className="similarity-hint">
          To see similarity: pick <strong>different user IDs</strong> (e.g. alice, then bob), enter the same or similar text in each,
          and submit. Ensure <code>vector-service</code> is running (<code>docker compose ps</code>).
        </p>
      </section>
    );
  }

  const maxScore = Math.max(...similarUsers.map((u) => u.similarityScore), 0.01);

  const minRadius = 60;
  const maxRadius = 140;

  return (
    <section className="similarity-section">
      <h2 className="section-title">Similarity with other users</h2>
      <p className="similarity-intro">
        {similarUsers.length} user(s) have sent prompts similar to yours.
      </p>
      <p className="similarity-notification-note">
        When similarity is detected, the backend sends an alert to the notification service (see notification-service container logs). You can wire it to email, Slack, or in-app alerts in production.
      </p>

      {/* Network-style visualization: center = you, nodes = similar prompts; edge length encodes similarity */}
      <div className="similarity-graph">
        <svg className="similarity-graph-svg" viewBox="-160 -160 320 320" aria-hidden="true">
          {similarUsers.map((u, i) => {
            const angle = (360 / similarUsers.length) * i - 90;
            const rad = (angle * Math.PI) / 180;
            const frac = u.similarityScore / maxScore;
            const radius = minRadius + (maxRadius - minRadius) * (1 - frac); // closer = higher similarity
            const x = Math.cos(rad) * radius;
            const y = Math.sin(rad) * radius;
            const midX = x * 0.55;
            const midY = y * 0.55;
            const label =
              `${(u.similarityScore * 100).toFixed(0)}% · ` +
              (u.textPreview && u.textPreview.length > 40 ? u.textPreview.slice(0, 40) + '…' : u.textPreview ?? u.promptId.slice(0, 8));
            return (
              <g key={`${u.promptId}-edge`}>
                <line x1={0} y1={0} x2={x} y2={y} className="graph-edge-line" />
                <text x={midX} y={midY} className="graph-edge-label">
                  {label}
                </text>
              </g>
            );
          })}
        </svg>

        <div className="graph-center-node" title={currentPromptId}>
          <span className="graph-label">You</span>
          <span className="graph-user">{currentUserId}</span>
        </div>
        {similarUsers.map((u, i) => {
          const angle = (360 / similarUsers.length) * i - 90;
          const rad = (angle * Math.PI) / 180;
          const frac = u.similarityScore / maxScore;
          const radius = minRadius + (maxRadius - minRadius) * (1 - frac);
          const x = Math.cos(rad) * radius;
          const y = Math.sin(rad) * radius;
          return (
            <div
              key={u.promptId}
              className="graph-outer-node-wrapper"
              style={{
                transform: `translate(calc(-50% + ${x}px), calc(-50% + ${y}px))`,
              }}
            >
              <div className="graph-outer-node">
                <span className="graph-score">{(u.similarityScore * 100).toFixed(0)}%</span>
                <span className="graph-user">{u.userId}</span>
                {u.textPreview ? (
                  <span className="graph-prompt-preview" title={u.textPreview}>
                    {u.textPreview.length > 40 ? u.textPreview.slice(0, 40) + '…' : u.textPreview}
                  </span>
                ) : (
                  <span className="graph-prompt-id" title={u.promptId}>
                    {u.promptId.slice(0, 8)}…
                  </span>
                )}
              </div>
            </div>
          );
        })}
      </div>

      {/* List view with score bars */}
      <ul className="similarity-list">
        {similarUsers.map((u) => (
          <li key={u.promptId} className="similarity-card">
            <div className="similarity-card-header">
              <span className="similarity-user">{u.userId}</span>
              <span className="similarity-score" title="Similarity score">
                {(u.similarityScore * 100).toFixed(1)}%
              </span>
            </div>
            <div className="similarity-score-bar">
              <div
                className="similarity-score-fill"
                style={{ width: `${(u.similarityScore / maxScore) * 100}%` }}
              />
            </div>
            <div className="similarity-prompt-id">Prompt ID: {u.promptId.slice(0, 12)}…</div>
            {u.textPreview && (
              <div className="similarity-prompt-text" title={u.textPreview}>
                {u.textPreview.length > 150 ? u.textPreview.slice(0, 150) + '…' : u.textPreview}
              </div>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
};
