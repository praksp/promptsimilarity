# Plugin: Realtime Similarity and Choice Flow – Validation

## Current state

### Does the plugin use the notification service or search service?

**No.** The plugin (extension + hook) only talks to the **API gateway** over HTTP:

- **Extension:** `GET /api/v1/prompts/rag/stats`, `POST /api/v1/prompts/ingest`, `GET /api/v1/prompts/similar?text=...`
- **Hook:** `POST /api/v1/prompts/ingest`, `POST /api/v1/prompts/rag/similar-responses`, `POST /api/v1/prompts/cursor-response`

The **notification service** and **search service** are used **inside the backend** (e.g. ingest → vector/search/graph; similarity alerts). The plugin never calls them directly. The gateway is the single entry point.

### Does the plugin do realtime “as you type” similarity?

**No.** Today:

- **Hook** runs only on **beforeSubmitPrompt** (when the user presses Enter in the agent chat). There is no hook that fires “as the user types” in the composer. Cursor does not expose composer content or “onComposerInputChange” to extensions or hooks.
- **Extension** does not call **live-similar**. The dashboard calls `GET /api/v1/prompts/live-similar?text=...` with debounced input to show “similar prompts while you type”; the plugin has no equivalent and has no access to the agent chat input text while the user is typing.

So the plugin does **not** track prompt similarity in real time as the prompt is typed, and does **not** surface similar prompts before the user presses Enter.

### When the user selects a similar prompt, does the plugin update metrics (tokens saved, reuses, etc.)?

**Only in one case.** If the hook **short-circuits** (match from similar-responses with score ≥ 0.5 and cached response), it returns `continue: false` with `user_message` and the agent is not called. The backend does **not** get a “user chose cached response” signal, so **rag/feedback** and **rag/record-satisfied** are never called from the plugin. So:

- **Tokens saved / reuses** in the backend are updated only when the **dashboard** user clicks “Use this” (which calls rag/feedback + rag/record-satisfied). The plugin’s short-circuit does **not** call those endpoints, so the plugin’s “reuse” does not currently update tokens saved or reuse count.
- **Prompt counter** is incremented only on **ingest**. When we short-circuit we skip ingest, so the prompt count does not increase for that submission (which is correct for “reuse”).

So today: short-circuit avoids the agent call but does **not** update “tokens saved” or “reuses” in the backend.

### If the user ignores the suggestion and sends the prompt to the agent, is the prompt/response stored?

**Yes.** When the hook does **not** short-circuit, it runs the existing flow: ingest (prompt stored), then `continue: true` so the agent runs, then the stop hook sends the response via **cursor-response** with **promptId**, so the backend stores the response. So “user insists on sending to agent” is already supported and prompt + response are stored.

---

## What would need to change to match the intended design

### 1. Realtime similarity “as the user types”

**Constraint:** Cursor does **not** expose the agent chat input content to extensions or hooks while the user is typing. So we cannot run live-similar on the “real” composer text from the extension or the hook.

**Options:**

- **A) Custom input in the extension**  
  Add a text field (or webview) in the Prompt Similarity view. As the user types there (e.g. debounced 300–400 ms), the extension calls **GET /api/v1/prompts/live-similar?text=...** (same as the dashboard). Show a list of similar prompts (and optionally cached response preview).  
  - **Limitation:** Submitting “to the agent” would require the user to copy the text into the real Cursor chat and press Enter (we cannot submit to the agent from the extension). So this is a “draft in our panel, then paste into chat” flow unless Cursor adds an API to submit to the agent.

- **B) Rely on Cursor**  
  If Cursor ever adds a hook like `onComposerInputChange` or an API to read composer content, we could call live-similar from the hook or extension in real time and surface suggestions (e.g. via a status bar or a Cursor UI extension point). Today this does not exist.

**Recommendation:** Implement **Option A** (custom input in the extension that calls **live-similar** and shows similar prompts as you type). For “send to agent,” document that the user pastes into the chat and presses Enter (hook then runs as today). Optionally add a “Copy to chat” that copies the text so the user only has to paste and Enter.

### 2. Surface similar prompts and let the user choose

- In the extension UI (e.g. under the same view as “Last similar”):
  - When live-similar returns results (from the custom input), show them with **score** and **prompt preview**.
  - For each similar prompt, if the backend can return **cached response** for that prompt, show a “Use this” action. That requires either:
    - **live-similar** to be extended to return **responseText** for each match (from responseStore by promptId), or
    - A separate call like **GET /api/v1/prompts/rag/similar-responses** (POST) with the **selected similar prompt’s text** (or promptId) to get the cached response for that one prompt. Today **similar-responses** takes the **current** prompt text and returns matches with responseText; we could add an endpoint “get cached response for promptId” or reuse similar-responses with the chosen prompt text.
- When the user clicks **“Use this”** on a similar prompt:
  - Show the cached response in the extension (e.g. in the tree view or a webview).
  - Call **POST /api/v1/prompts/rag/feedback** with `responseId` and `satisfied: true`, and **POST /api/v1/prompts/rag/record-satisfied** with the current prompt text and the similar prompt’s promptId, so the backend updates **tokens saved**, **reuses**, and **prompt count** (if you want to count “reuse” as a prompt event).
  - Do **not** call the agent (no need to paste into chat).

### 3. If the user does not choose a suggestion and sends to the agent

- Keep current behavior: user types in the **real** Cursor chat and presses Enter.
- Hook runs: similar-responses first; if no short-circuit, ingest then `continue: true`; agent runs; stop hook sends cursor-response with promptId so prompt + response are stored. No change needed.

### 4. Notification service and search service

- **Notification service:** Used by the backend to notify when **another user** has a similar prompt. The plugin does not need to call it; the backend can keep using it after ingest. Optional: extension could subscribe to a “notifications” API if the backend exposes one (e.g. WebSocket or polling), to show “X users had similar prompts” in the IDE. Not required for “realtime as you type” or “choose similar / send to agent.”
- **Search service:** Used by the backend for indexing and search. **live-similar** and **similar-responses** use the **vector service** (embeddings + vector search), not the search service directly. So for “realtime similarity” the plugin only needs the gateway endpoints that already use the vector pipeline (live-similar, similar-responses). No change needed to the plugin’s use of “search service” (it doesn’t use it).

---

## Summary table

| Requirement | Current state | Change needed |
|-------------|---------------|----------------|
| Realtime similarity as user types | Not implemented; no access to composer text | Add extension UI with text input; call **live-similar** (debounced) as user types there. |
| Surface similar prompts in IDE | Only “Last similar” after submit (from hook) | In the same view, show live-similar results and, when available, cached response + “Use this.” |
| User selects similar → show cached response, no agent call, update metrics | Short-circuit in hook updates nothing in backend; no “Use this” in extension | Extension “Use this” → call **rag/feedback** + **rag/record-satisfied**; show cached response in UI; no agent call. |
| User sends to agent anyway | Supported (ingest + cursor-response) | No change. |
| Use notification service | Plugin does not use it | Optional later: backend can expose notifications; extension can display them. |
| Use search service | Plugin does not use it | Not required for realtime similarity (vector + gateway is enough). |

---

## Recommended implementation order

1. **Extension: live-similar panel**  
   Add a text input in the Prompt Similarity view. On debounced input (e.g. 400 ms), call **GET /api/v1/prompts/live-similar**. Show results (promptId, userId, score, textPreview). Reuse or extend backend so that for each match we can get **responseText** (e.g. endpoint “get response for promptId” or similar-responses with that prompt text).

2. **Extension: “Use this” and metrics**  
   For each shown similar prompt that has a cached response, add “Use this.” On click: show response in UI, call **rag/feedback** (satisfied: true) and **rag/record-satisfied** so tokens saved, reuses, and (if desired) prompt count are updated.

3. **Extension: “Send to agent”**  
   Document that the user pastes the text into the Cursor chat and presses Enter (hook handles the rest). Optionally add “Copy to chat” (copy to clipboard) for the current text.

4. **Hook (optional)**  
   Keep current short-circuit behavior. Optionally have the hook call **rag/feedback** when it short-circuits (if we have a responseId from similar-responses), so that one reuse is also reflected in backend metrics.

I can implement the extension changes (1–3) and the optional hook change (4) next if you want to proceed in this direction.
