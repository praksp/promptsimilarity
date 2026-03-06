# Prompt Similarity – Cursor integration

This extension connects Cursor’s agent chat to your Prompt Similarity service so that:

- **Every agent prompt** is sent to the service (ingested and checked for similar prompts).
- **RAG impact** (tokens saved, reuse count) is shown in the IDE (status bar and sidebar).

Tokens saved and all RAG metrics are the same as on the dashboard because both use the same backend. See [../docs/DASHBOARD_PLUGIN_CONSISTENCY.md](../docs/DASHBOARD_PLUGIN_CONSISTENCY.md).

---

## Do this first: load the extension

The **Enable Prompt Similarity** command only appears when the extension is running in the **current** window. If you press `Cmd+Shift+P` (Mac) or `Ctrl+Shift+P` (Windows/Linux), type **Enable Prompt**, and see no matching command, the extension is **not** loaded in this window.

**Quick check:** Look at the **bottom-right status bar**. Do you see **Prompt Similarity: offline** (or **RAG: … tokens saved**)?
- **No** → Extension not loaded. Use Option A or B below.
- **Yes** → Extension is loaded. Use Command Palette again or click the **Prompt Similarity** icon in the left sidebar, then the **plug icon** in the panel.

---

## Is the extension loaded?

If **Enable Prompt Similarity** does not appear in the Command Palette (`Cmd+Shift+P` / `Ctrl+Shift+P`), the extension is not active in this Cursor window. Use the checks below.

### 1. Check the Extensions list

- **View → Extensions** (or `Cmd+Shift+X` / `Ctrl+Shift+X`).
- Search for **"Prompt Similarity"** or **"prompt-similarity-cursor"**.
- If it appears: ensure it is **Enabled** (no "Disable" action shown).
- If it does **not** appear: the extension is not installed. Use **Option A** or **Option B** below.

### 2. Option A – Run from source (development)

To load the extension from the repo without installing a VSIX:

1. **Open the plugin folder in Cursor:** **File → Open Folder** → choose the **`cursor-plugin`** folder (inside the Prompt similarity repo).
2. **Build the extension:** in a terminal, from `cursor-plugin` run:
   ```bash
   npm install && npm run build
   ```
3. **Launch the Extension Development Host:** press **F5** (or **Run → Start Debugging**).
4. A **new Cursor window** opens with **"[Extension Development Host]"** in the title. The extension is loaded only in that window.
5. In that new window, open your **project folder** (File → Open Folder → your Prompt similarity repo or project).
6. In that window, press **Cmd+Shift+P** / **Ctrl+Shift+P**, type **Enable Prompt** — you should see **"Enable Prompt Similarity integration (agent prompts → service)"**.

### 3. Option B – Install from a VSIX

1. In the `cursor-plugin` folder, run:
   ```bash
   npm install && npm run build && npx vsce package --no-dependencies
   ```
2. A `.vsix` file is created. In Cursor: **View → Extensions** → **"..."** (top right) → **Install from VSIX...** → select that `.vsix` file.
3. **Reload the window:** Command Palette → **"Developer: Reload Window"**.
4. Open a folder (File → Open Folder), then try the Command Palette again for **"Enable Prompt Similarity"**.

### 4. After the extension is loaded

- You should see **"Prompt Similarity: offline"** (or token counts) in the **status bar (bottom right)**.
- The **Prompt Similarity** icon should appear in the **left Activity Bar** (left edge). Click it to open the RAG & Similar view; the **plug icon** in that view runs Enable integration.

---

## One-time setup

1. **Start the Prompt Similarity stack** (API gateway and services). For example:
   ```bash
   ./scripts/clean-build-run.sh
   ```
   The gateway should be at `http://localhost:8080` (or set `promptSimilarity.gatewayUrl` in Cursor settings).

2. **Enable integration in this workspace**
   - **Command Palette:** `Cmd+Shift+P` (Mac) or `Ctrl+Shift+P` (Windows/Linux) → type **Enable Prompt** → pick **Enable Prompt Similarity integration (agent prompts → service)**.  
   - **Or** open the **Prompt Similarity** view in the left sidebar (icon in Activity Bar) and click the **plug icon** in the panel title bar.  
   - This writes:
     - `.cursor/prompt-similarity.json` (gateway URL, user ID, org ID from your settings)
     - `.cursor/hooks.json` (Cursor hook so each agent prompt is sent to the service)

3. **Restart Cursor** if the hook does not run (e.g. first time after enabling).

## What you see in the IDE

- **Status bar (bottom right):**  
  - **“Prompts: N | RAG: X saved (N = total prompts ingested)”** when the gateway is reachable.  
  - **“Prompt Similarity: offline”** when the gateway is not reachable.

- **Sidebar – Prompt Similarity view:**  
  - **Prompt count:** total prompts ingested.  
  - **RAG impact:** same totals (tokens saved, reuses).  
  - **Last hook run:** when the hook last ran and whether ingest/similar succeeded (use to verify intercept).  
  - **Last similar (from agent):** similar prompts/responses found for the last agent prompt (from the hook). Refreshes when `.cursor/last-similar.json` is updated (after each agent submit).

- **Live Similar (as you type):** Click **"Open Live Similar (type to find similar prompts)"** in the Prompt Similarity view title bar (or run that command from the Command Palette). A panel opens where you type your prompt; after at least 8 characters, similar prompts (40%+ similarity) appear. You can **Use this (cached)** to apply the stored response (no agent call; tokens saved and reuses updated), or **Copy prompt to clipboard** and paste in the Cursor chat to send to the agent.

- **Refresh:** Run **“Refresh RAG stats and last similar”** from the Command Palette to update the view and status bar.

## Repeat queries and cached responses (short-circuit)

- **First time** you send a prompt: the hook calls `similar-responses` (no match), then ingests the prompt, and returns `continue: true` so the agent runs. The stop hook sends the agent’s response to the backend (`cursor-response` with the prompt id). The backend stores that response for RAG.
- **Second time** you send the same (or very similar) prompt: the hook calls `similar-responses` **first** (realtime similarity, like the dashboard). If the backend returns a cached match with similarity score **≥ 0.5** and response text, the hook calls **rag/feedback** and **rag/record-satisfied** so tokens saved and reuses are updated, then returns **`continue: false`** with **`user_message`** set to the cached response. Cursor then **does not call the agent** and shows that message to you (saving tokens). If there is no match above the threshold, the hook ingests and returns `continue: true` as before.
- **If short-circuit doesn’t happen:** (1) The first response may not have been stored—ensure the **stop** hook ran and sent `cursor-response` with the same workspace so `promptId` was linked. (2) The **vector store** is in-memory; if the backend restarted between the two prompts, the first prompt’s embedding is gone and there is no match. (3) Backend similarity threshold is 0.65; the hook short-circuits when score ≥ 0.5.

**User context:** The hook uses `userId` and `orgId` from `.cursor/prompt-similarity.json` (written when you run **Enable integration** from the extension). Keep the same workspace and settings so the same user is used; then repeat queries from that user will match and show the cached response in **Last similar**.

If **Cursor prompts don’t show in the dashboard** (“All prompts in the system”): enable **“Load prompts on demand”** in that section, ensure integration is enabled and the gateway is reachable at `http://localhost:8080`, and see [../docs/PERSISTENCE_AND_DATA.md](../docs/PERSISTENCE_AND_DATA.md) for more causes.

## What “tokens saved” means

- The backend counts **tokens saved** when someone uses the **dashboard** and chooses a **cached similar answer** instead of calling the LLM.
- The number in the IDE is the **same org-level RAG impact** (total tokens saved and reuse count). It is not a per-Cursor-session token count.

## Disable integration

- Command Palette → **“Disable Prompt Similarity integration”**  
- This removes the Prompt Similarity hook from `.cursor/hooks.json`. Your agent prompts will no longer be sent to the service.

## Other commands

- **Open Live Similar (type to find similar prompts)** – open a panel to type a prompt; similar prompts appear as you type; choose one to use the cached response (metrics updated) or copy the prompt to paste in the Cursor chat.
- **Send current selection to Prompt Similarity** – ingest the selected text (or whole document) without using the agent.
- **Find similar prompts** – search for similar prompts (selection or query).
