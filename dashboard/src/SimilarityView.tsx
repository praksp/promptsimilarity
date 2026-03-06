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

/** Edge color by similarity: low = orange (far), mid = yellow, high = green (close) */
function edgeColor(score: number): string {
  if (score >= 0.7) return 'var(--edge-close)';
  if (score >= 0.4) return 'var(--edge-mid)';
  return 'var(--edge-far)';
}

/** Edge length in px: higher similarity = shorter (closer nodes) */
function edgeLengthPx(score: number): number {
  const minLen = 28;
  const maxLen = 80;
  return minLen + (1 - score) * (maxLen - minLen);
}

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

  const cx = 200;
  const cy = 180;
  const centerRadius = 50;

  return (
    <section className="similarity-section">
      <h2 className="section-title">Similarity with other users</h2>
      <p className="similarity-intro">
        Nodes: prompts and users. Edges: <strong>similar prompt</strong> (length = similarity; green = close, yellow = mid, orange = far).
      </p>

      <div className="similarity-graph similarity-graph-new">
        <svg className="similarity-graph-svg" viewBox="0 0 400 360" aria-hidden="true">
          {/* Current prompt node (center) */}
          <circle cx={cx} cy={cy - 20} r="24" className="graph-node graph-node-prompt graph-node-center">
            <title>{currentPromptId}</title>
          </circle>
          <text x={cx} y={cy - 20} textAnchor="middle" dominantBaseline="middle" className="graph-node-label graph-node-label-center">
            Your prompt
          </text>
          {/* Current user node (below center) */}
          <circle cx={cx} cy={cy + 28} r="22" className="graph-node graph-node-user graph-node-center" />
          <text x={cx} y={cy + 28} textAnchor="middle" dominantBaseline="middle" className="graph-node-label graph-node-label-center">
            {currentUserId}
          </text>
          {/* Edge: current prompt — current user (you) */}
          <line
            x1={cx} y1={cy - 20 + 24}
            x2={cx} y2={cy + 28 - 22}
            className="graph-edge-line graph-edge-you"
            stroke="var(--muted)"
            strokeWidth="1.5"
            strokeDasharray="4 2"
          />

          {similarUsers.map((u, i) => {
            const angle = (360 / similarUsers.length) * i - 90;
            const rad = (angle * Math.PI) / 180;
            const dist = centerRadius + 70 + (1 - u.similarityScore) * 60;
            const px = cx + Math.cos(rad) * dist;
            const py = cy + Math.sin(rad) * dist;
            const edgeLen = edgeLengthPx(u.similarityScore);
            const ux = px + Math.cos(rad) * edgeLen;
            const uy = py + Math.sin(rad) * edgeLen;
            const color = edgeColor(u.similarityScore);
            const midX = (px + ux) / 2;
            const midY = (py + uy) / 2;
            const label = `similar prompt ${(u.similarityScore * 100).toFixed(0)}%`;
            return (
              <g key={`${u.promptId}-${u.userId}`}>
                <line
                  x1={px} y1={py}
                  x2={ux} y2={uy}
                  className="graph-edge-line graph-edge-similar"
                  stroke={color}
                  strokeWidth="2"
                />
                <text x={midX} y={midY} textAnchor="middle" dominantBaseline="middle" className="graph-edge-label">
                  {label}
                </text>
                <circle cx={px} cy={py} r="20" className="graph-node graph-node-prompt" />
                <text x={px} y={py} textAnchor="middle" dominantBaseline="middle" className="graph-node-label" fontSize="9">
                  {(u.textPreview && u.textPreview.length > 12 ? u.textPreview.slice(0, 12) + '…' : u.textPreview) || u.promptId.slice(0, 8)}
                </text>
                <circle cx={ux} cy={uy} r="18" className="graph-node graph-node-user" />
                <text x={ux} y={uy} textAnchor="middle" dominantBaseline="middle" className="graph-node-label" fontSize="10">
                  {u.userId}
                </text>
              </g>
            );
          })}
        </svg>
      </div>

      <ul className="similarity-list">
        {similarUsers.map((u) => (
          <li key={u.promptId} className="similarity-card">
            <div className="similarity-card-header">
              <span className="similarity-user">{u.userId}</span>
              <span className="similarity-score" title="Similarity score" style={{ color: edgeColor(u.similarityScore) }}>
                {(u.similarityScore * 100).toFixed(1)}%
              </span>
            </div>
            <div className="similarity-score-bar">
              <div
                className="similarity-score-fill"
                style={{ width: `${u.similarityScore * 100}%`, background: edgeColor(u.similarityScore) }}
              />
            </div>
            {u.textPreview && (
              <div className="similarity-prompt-text" title={u.textPreview}>
                {u.textPreview.length > 80 ? u.textPreview.slice(0, 80) + '…' : u.textPreview}
              </div>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
};
