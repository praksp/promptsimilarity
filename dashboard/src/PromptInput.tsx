import { type FC } from 'react';

type Props = {
  value: string;
  onChange: (v: string) => void;
  userId: string;
  onUserIdChange: (v: string) => void;
  onSubmit: () => void;
  busy: boolean;
  placeholder?: string;
};

export const PromptInput: FC<Props> = ({
  value,
  onChange,
  userId,
  onUserIdChange,
  onSubmit,
  busy,
  placeholder = 'Enter your prompt… e.g. "How do I implement JWT authentication in Spring Boot?"',
}) => {
  return (
    <section className="prompt-input-section">
      <h2 className="section-title">Save prompt (find similar)</h2>
      <p className="prompt-input-hint">Saves your prompt and finds similar ones from other users. Does not call the LLM.</p>
      <textarea
        className="prompt-textarea"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        rows={4}
        disabled={busy}
      />
      <div className="prompt-actions">
        <label className="user-label">
          <span>User (simulate multi-user)</span>
          <input
            type="text"
            className="user-input"
            value={userId}
            onChange={(e) => onUserIdChange(e.target.value)}
            placeholder="e.g. alice, bob, charlie"
            disabled={busy}
          />
        </label>
        <button
          type="button"
          className="submit-btn"
          onClick={onSubmit}
          disabled={busy || !value.trim()}
        >
          {busy ? 'Sending…' : 'Save prompt & find similar'}
        </button>
      </div>
    </section>
  );
};
