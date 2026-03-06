import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';

// Same gateway as dashboard (default :8080) so tokens saved and all metrics stay in sync.
const DEFAULT_GATEWAY = 'http://localhost:8080';
const HOOK_SCRIPT_NAME = 'prompt-similarity-hook.js';

function getConfig() {
  const config = vscode.workspace.getConfiguration('promptSimilarity');
  return {
    gatewayUrl: (config.get<string>('gatewayUrl') || DEFAULT_GATEWAY).replace(/\/$/, ''),
    orgId: config.get<string>('orgId') || 'default-org',
    userId: config.get<string>('userId') || require('os').hostname() || 'anonymous',
  };
}

async function ingestPrompt(text: string): Promise<{ promptId: string; similarityDetected: boolean; similarUsers: Array<{ userId: string; promptId: string; similarityScore: number }> }> {
  const { gatewayUrl, orgId, userId } = getConfig();
  const res = await fetch(`${gatewayUrl}/api/v1/prompts/ingest`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ userId, orgId, text, language: 'en' }),
  });
  if (!res.ok) throw new Error(`Ingest failed: ${res.status} ${await res.text()}`);
  return res.json() as Promise<{ promptId: string; similarityDetected: boolean; similarUsers: Array<{ userId: string; promptId: string; similarityScore: number }> }>;
}

async function findSimilar(text: string, topK = 10): Promise<Array<{ promptId: string; userId: string; score: number; textPreview: string }>> {
  const { gatewayUrl, orgId, userId } = getConfig();
  const params = new URLSearchParams({ text, orgId, userId, topK: String(topK), minScore: '0.7' });
  const res = await fetch(`${gatewayUrl}/api/v1/prompts/similar?${params}`);
  if (!res.ok) throw new Error(`Find similar failed: ${res.status}`);
  return res.json() as Promise<Array<{ promptId: string; userId: string; score: number; textPreview: string }>>;
}

/** Live similar: as-you-type similarity (minScore 0.4). Used by the Live Similar panel. */
async function liveSimilar(text: string, topK = 10): Promise<Array<{ promptId: string; userId: string; score: number; textPreview: string }>> {
  const { gatewayUrl, orgId, userId } = getConfig();
  const params = new URLSearchParams({ text: text.trim(), orgId, userId, topK: String(topK), minScore: '0.4' });
  const res = await fetch(`${gatewayUrl}/api/v1/prompts/live-similar?${params}`, { signal: AbortSignal.timeout(10000) });
  if (!res.ok) return [];
  const data = await res.json();
  return Array.isArray(data) ? data : [];
}

interface SimilarResponseMatch {
  promptId: string;
  responseId: string;
  promptPreview: string;
  responseText: string;
  similarityScore: number;
  tokensUsed: number;
}
async function ragSimilarResponses(prompt: string): Promise<SimilarResponseMatch[]> {
  const { gatewayUrl, orgId, userId } = getConfig();
  const res = await fetch(`${gatewayUrl}/api/v1/prompts/rag/similar-responses`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ prompt: prompt.trim(), userId, orgId }),
    signal: AbortSignal.timeout(10000),
  });
  if (!res.ok) return [];
  const data = await res.json();
  return Array.isArray(data) ? data : [];
}

async function ragFeedback(responseId: string, satisfied: boolean): Promise<void> {
  const { gatewayUrl, orgId } = getConfig();
  await fetch(`${gatewayUrl}/api/v1/prompts/rag/feedback`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ responseId, satisfied, orgId }),
    signal: AbortSignal.timeout(5000),
  });
}

async function ragRecordSatisfied(promptText: string, similarToPromptId: string, similarityScore: number): Promise<string> {
  const { gatewayUrl, orgId, userId } = getConfig();
  const res = await fetch(`${gatewayUrl}/api/v1/prompts/rag/record-satisfied`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ promptText, userId, orgId, similarToPromptId, similarityScore }),
    signal: AbortSignal.timeout(5000),
  });
  if (!res.ok) throw new Error(`record-satisfied failed: ${res.status}`);
  const data = (await res.json()) as { promptId?: string };
  return data.promptId ?? '';
}

type RagStats = { tokensSavedTotal: number; tokensSavedThisMonth: number; reuseCount: number; promptCount?: number; prompt_count?: number };
async function fetchRagStats(): Promise<RagStats | null> {
  const { gatewayUrl, orgId } = getConfig();
  try {
    const res = await fetch(`${gatewayUrl}/api/v1/prompts/rag/stats?orgId=${encodeURIComponent(orgId)}`, { signal: AbortSignal.timeout(5000) });
    if (!res.ok) return null;
    return (await res.json()) as RagStats;
  } catch {
    return null;
  }
}

function getWorkspaceCursorDir(): string | undefined {
  const folder = vscode.workspace.workspaceFolders?.[0];
  return folder ? path.join(folder.uri.fsPath, '.cursor') : undefined;
}

interface LastRunPayload {
  event?: string;
  timestamp?: number;
  configFound?: boolean;
  ingestOk?: boolean;
  similarOk?: boolean;
  error?: string;
  workspaceRoot?: string;
}
function readLastRun(): LastRunPayload | null {
  const cursorDir = getWorkspaceCursorDir();
  if (!cursorDir) return null;
  const file = path.join(cursorDir, 'prompt-similarity-last-run.json');
  try {
    if (!fs.existsSync(file)) return null;
    return JSON.parse(fs.readFileSync(file, 'utf8')) as LastRunPayload;
  } catch {
    return null;
  }
}

function getHookScriptPath(extensionPath: string): string {
  return path.join(extensionPath, 'scripts', HOOK_SCRIPT_NAME);
}

const MIN_LIVE_SIMILAR_CHARS = 8;
const LIVE_SIMILAR_DEBOUNCE_MS = 400;

function getLiveSimilarWebviewContent(webview: vscode.Webview): string {
  const nonce = Buffer.from([Date.now(), Math.random()]).toString('base64').replace(/[^a-zA-Z0-9]/g, '');
  return `<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'nonce-${nonce}'; style-src 'unsafe-inline' ${webview.cspSource};">
  <style>
    body { font-family: var(--vscode-font-family); font-size: var(--vscode-font-size); padding: 12px; margin: 0; color: var(--vscode-foreground); }
    h2 { margin: 0 0 8px 0; font-size: 1.1em; }
    .hint { color: var(--vscode-descriptionForeground); font-size: 0.9em; margin-bottom: 12px; }
    input { width: 100%; padding: 8px; box-sizing: border-box; margin-bottom: 12px; background: var(--vscode-input-background); color: var(--vscode-input-foreground); border: 1px solid var(--vscode-input-border); }
    .status { font-size: 0.9em; margin-bottom: 8px; min-height: 1.2em; }
    ul { list-style: none; padding: 0; margin: 0; }
    li { padding: 10px; margin-bottom: 8px; background: var(--vscode-editor-inactiveSelectionBackground); border-radius: 4px; }
    .card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
    .score { font-weight: bold; color: var(--vscode-textLink-foreground); }
    .preview { font-size: 0.9em; color: var(--vscode-descriptionForeground); margin-bottom: 6px; word-break: break-word; }
    .actions { margin-top: 6px; }
    button { margin-right: 8px; padding: 4px 10px; cursor: pointer; font-size: 0.9em; background: var(--vscode-button-background); color: var(--vscode-button-foreground); border: none; border-radius: 2px; }
    button:hover { background: var(--vscode-button-hoverBackground); }
    button.secondary { background: var(--vscode-button-secondaryBackground); color: var(--vscode-button-secondaryForeground); }
    button.secondary:hover { background: var(--vscode-button-secondaryHoverBackground); }
    .copy-prompt { margin-top: 12px; }
    .response-box { margin-top: 16px; padding: 12px; background: var(--vscode-editor-background); border: 1px solid var(--vscode-input-border); border-radius: 4px; white-space: pre-wrap; word-break: break-word; max-height: 200px; overflow-y: auto; }
    .error { color: var(--vscode-errorForeground); }
  </style>
</head>
<body>
  <h2>Similar prompts as you type</h2>
  <p class="hint">Type at least ${MIN_LIVE_SIMILAR_CHARS} characters. Matching prompts (40%+ similarity) appear below. Choose one to use the cached response and save tokens.</p>
  <input type="text" id="input" placeholder="Type your prompt here..." autocomplete="off" />
  <div class="status" id="status"></div>
  <ul id="results"></ul>
  <div class="copy-prompt">
    <button type="button" id="copyBtn" disabled>Copy prompt to clipboard (paste in Cursor chat)</button>
  </div>
  <div id="responseSection" style="display:none;">
    <h3>Cached response</h3>
    <div class="response-box" id="responseBox"></div>
  </div>
  <script nonce="${nonce}">
    const vscode = acquireVsCodeApi();
    const input = document.getElementById('input');
    const status = document.getElementById('status');
    const results = document.getElementById('results');
    const copyBtn = document.getElementById('copyBtn');
    const responseSection = document.getElementById('responseSection');
    const responseBox = document.getElementById('responseBox');
    let debounceTimer = null;
    let lastText = '';
    input.addEventListener('input', () => {
      const text = input.value.trim();
      lastText = text;
      copyBtn.disabled = !text;
      if (debounceTimer) clearTimeout(debounceTimer);
      if (text.length < ${MIN_LIVE_SIMILAR_CHARS}) {
        status.textContent = text.length ? 'Type at least ${MIN_LIVE_SIMILAR_CHARS} characters.' : '';
        results.innerHTML = '';
        return;
      }
      status.textContent = 'Searching...';
      results.innerHTML = '';
      debounceTimer = setTimeout(() => { vscode.postMessage({ type: 'liveSimilar', text }); }, ${LIVE_SIMILAR_DEBOUNCE_MS});
    });
    copyBtn.addEventListener('click', () => {
      if (lastText) vscode.postMessage({ type: 'copyToChat', text: lastText });
    });
    window.addEventListener('message', (e) => {
      const msg = e.data;
      if (msg.type === 'liveSimilarResult') {
        status.textContent = msg.error || (msg.results.length === 0 ? 'No similar prompts at 40%+.' : msg.results.length + ' similar prompt(s) found.');
        if (msg.error) { results.innerHTML = ''; return; }
        results.innerHTML = msg.results.map(r => {
          const preview = (r.textPreview || '').slice(0, 120) + ((r.textPreview || '').length > 120 ? '…' : '');
          return '<li data-prompt-id="' + (r.promptId || '') + '" data-score="' + (r.score ?? 0) + '"><div class="card-header"><span class="score">' + Math.round((r.score || 0) * 100) + '%</span></div><div class="preview">' + escapeHtml(preview) + '</div><div class="actions"><button class="use-this">Use this (cached)</button></div></li>';
        }).join('');
        results.querySelectorAll('.use-this').forEach((btn, i) => {
          btn.addEventListener('click', () => {
            const li = btn.closest('li');
            const promptId = li.dataset.promptId;
            const score = parseFloat(li.dataset.score || '0');
            vscode.postMessage({ type: 'useThis', promptId, currentText: input.value.trim(), score });
          });
        });
      } else if (msg.type === 'useThisResult') {
        if (msg.error) { status.textContent = msg.error; status.classList.add('error'); responseSection.style.display = 'none'; return; }
        status.textContent = 'Using cached response. Tokens saved.';
        status.classList.remove('error');
        responseBox.textContent = msg.responseText || '';
        responseSection.style.display = 'block';
      }
    });
    function escapeHtml(s) { const d = document.createElement('div'); d.textContent = s; return d.innerHTML; }
  </script>
</body>
</html>`;
}

let liveSimilarPanel: vscode.WebviewPanel | undefined;

function openLiveSimilarPanel(context: vscode.ExtensionContext, treeProvider: PromptSimilarityTreeProvider) {
  const title = 'Live Similar';
  if (liveSimilarPanel) {
    liveSimilarPanel.reveal();
    return;
  }
  liveSimilarPanel = vscode.window.createWebviewPanel(
    'promptSimilarityLiveSimilar',
    title,
    vscode.ViewColumn.One,
    { enableScripts: true, retainContextWhenHidden: true }
  );
  liveSimilarPanel.webview.html = getLiveSimilarWebviewContent(liveSimilarPanel.webview);
  liveSimilarPanel.onDidDispose(() => { liveSimilarPanel = undefined; });
  liveSimilarPanel.webview.onDidReceiveMessage(async (msg: { type: string; text?: string; promptId?: string; currentText?: string; score?: number }) => {
    if (msg.type === 'liveSimilar') {
      const text = (msg.text || '').trim();
      if (text.length < MIN_LIVE_SIMILAR_CHARS) {
        liveSimilarPanel?.webview.postMessage({ type: 'liveSimilarResult', results: [], error: null });
        return;
      }
      try {
        const results = await liveSimilar(text);
        liveSimilarPanel?.webview.postMessage({ type: 'liveSimilarResult', results, error: null });
      } catch (e) {
        liveSimilarPanel?.webview.postMessage({ type: 'liveSimilarResult', results: [], error: (e instanceof Error ? e.message : String(e)) });
      }
    } else if (msg.type === 'useThis') {
      const { promptId, currentText, score } = msg;
      if (!promptId || !currentText) {
        liveSimilarPanel?.webview.postMessage({ type: 'useThisResult', error: 'Missing prompt or selection.', responseText: null });
        return;
      }
      try {
        const matches = await ragSimilarResponses(currentText);
        const match = matches.find((m: SimilarResponseMatch) => m.promptId === promptId) || matches[0];
        if (!match || !match.responseId) {
          liveSimilarPanel?.webview.postMessage({ type: 'useThisResult', error: 'No cached response for this prompt.', responseText: null });
          return;
        }
        await ragFeedback(match.responseId, true);
        await ragRecordSatisfied(currentText, match.promptId, typeof score === 'number' ? score : match.similarityScore ?? 0);
        liveSimilarPanel?.webview.postMessage({ type: 'useThisResult', error: null, responseText: match.responseText ?? '' });
        updateStatusBar();
        treeProvider.refresh();
      } catch (e) {
        liveSimilarPanel?.webview.postMessage({ type: 'useThisResult', error: (e instanceof Error ? e.message : String(e)), responseText: null });
      }
    } else if (msg.type === 'copyToChat') {
      if (msg.text) {
        await vscode.env.clipboard.writeText(msg.text);
        vscode.window.showInformationMessage('Prompt copied. Paste it in the Cursor chat and press Enter to send to the agent.');
      }
    }
  });
  context.subscriptions.push(liveSimilarPanel);
}

async function enableIntegration(context: vscode.ExtensionContext) {
  const folder = vscode.workspace.workspaceFolders?.[0];
  if (!folder) {
    vscode.window.showWarningMessage('Open a workspace folder first.');
    return;
  }
  const cursorDir = path.join(folder.uri.fsPath, '.cursor');
  const configPath = path.join(cursorDir, 'prompt-similarity.json');
  const hooksPath = path.join(cursorDir, 'hooks.json');
  const hookScriptPath = getHookScriptPath(context.extensionPath);

  if (!fs.existsSync(hookScriptPath)) {
    vscode.window.showErrorMessage(`Hook script not found: ${hookScriptPath}. Reinstall the extension.`);
    return;
  }

  const cfg = getConfig();
  if (!fs.existsSync(cursorDir)) {
    fs.mkdirSync(cursorDir, { recursive: true });
  }
  fs.writeFileSync(configPath, JSON.stringify({
    gatewayUrl: cfg.gatewayUrl,
    userId: cfg.userId,
    orgId: cfg.orgId,
  }, null, 2), 'utf8');

  let hooks: { version?: number; hooks?: Record<string, unknown[]> } = { version: 1, hooks: {} };
  if (fs.existsSync(hooksPath)) {
    try {
      hooks = JSON.parse(fs.readFileSync(hooksPath, 'utf8'));
    } catch {}
  }
  if (!hooks.hooks) hooks.hooks = {};
  const command = `node "${hookScriptPath.replace(/\\/g, '/')}"`;
  hooks.hooks['beforeSubmitPrompt'] = [{ command }];
  hooks.hooks['stop'] = [{ command }];
  hooks.hooks['afterAgentResponse'] = [{ command }];
  fs.writeFileSync(hooksPath, JSON.stringify(hooks, null, 2), 'utf8');
  vscode.window.showInformationMessage('Prompt Similarity integration enabled. Agent prompts will be sent to the service. Restart Cursor if the hook does not run.');
}

async function disableIntegration() {
  const cursorDir = getWorkspaceCursorDir();
  if (!cursorDir) {
    vscode.window.showWarningMessage('No workspace folder open.');
    return;
  }
  const hooksPath = path.join(cursorDir, 'hooks.json');
  if (!fs.existsSync(hooksPath)) {
    vscode.window.showInformationMessage('Prompt Similarity hook was not enabled in this workspace.');
    return;
  }
  let hooks: { version?: number; hooks?: Record<string, unknown[]> };
  try {
    hooks = JSON.parse(fs.readFileSync(hooksPath, 'utf8'));
  } catch {
    vscode.window.showErrorMessage('Could not read .cursor/hooks.json');
    return;
  }
  const filterOurHook = (entries: unknown[]): unknown[] =>
    entries.filter((entry: unknown) => {
      const cmd = typeof entry === 'object' && entry !== null && 'command' in entry ? String((entry as { command: string }).command) : '';
      return !cmd.includes(HOOK_SCRIPT_NAME);
    });
  if (hooks.hooks?.beforeSubmitPrompt) {
    hooks.hooks.beforeSubmitPrompt = filterOurHook(hooks.hooks.beforeSubmitPrompt as unknown[]);
    if (hooks.hooks.beforeSubmitPrompt.length === 0) delete hooks.hooks.beforeSubmitPrompt;
  }
  if (hooks.hooks?.stop) {
    hooks.hooks.stop = filterOurHook(hooks.hooks.stop as unknown[]);
    if (hooks.hooks.stop.length === 0) delete hooks.hooks.stop;
  }
  if (hooks.hooks?.afterAgentResponse) {
    hooks.hooks.afterAgentResponse = filterOurHook(hooks.hooks.afterAgentResponse as unknown[]);
    if (hooks.hooks.afterAgentResponse.length === 0) delete hooks.hooks.afterAgentResponse;
  }
  fs.writeFileSync(hooksPath, JSON.stringify(hooks, null, 2), 'utf8');
  vscode.window.showInformationMessage('Prompt Similarity integration disabled.');
}

// --- Status bar ---
let statusBarItem: vscode.StatusBarItem | undefined;

function getPromptCount(stats: RagStats | null): number {
  if (!stats) return 0;
  const n = stats.promptCount ?? stats.prompt_count;
  return typeof n === 'number' && !Number.isNaN(n) ? n : 0;
}

function updateStatusBar() {
  if (!statusBarItem) return;
  fetchRagStats().then((stats) => {
    if (stats) {
      const promptCount = getPromptCount(stats);
      const promptsStr = promptCount.toLocaleString();
      // Always show prompt count first in the status bar (toolbar)
      statusBarItem!.text = `$(symbol-misc) ${promptsStr} prompts | RAG: ${stats.tokensSavedTotal.toLocaleString()} saved`;
      statusBarItem!.tooltip = `Prompts (used so far): ${promptsStr}\nTokens saved (total): ${stats.tokensSavedTotal.toLocaleString()}\nThis month: ${stats.tokensSavedThisMonth.toLocaleString()}\nReuses: ${stats.reuseCount}`;
    } else {
      statusBarItem!.text = '$(symbol-misc) Prompt Similarity: offline';
      statusBarItem!.tooltip = 'Gateway not reachable. Start the Prompt Similarity stack or check gateway URL in settings.';
    }
  });
}

// --- Sidebar tree (RAG stats + last similar) ---
interface LastSimilarPayload {
  promptPreview?: string;
  conversationId?: string;
  generationId?: string;
  timestamp?: number;
  similarMatches?: Array<{ promptId?: string; responseId?: string; promptPreview?: string; responseText?: string; similarityScore?: number; tokensUsed?: number }>;
}

class PromptSimilarityTreeProvider implements vscode.TreeDataProvider<vscode.TreeItem> {
  private _onDidChangeTreeData = new vscode.EventEmitter<vscode.TreeItem | undefined | void>();
  readonly onDidChangeTreeData = this._onDidChangeTreeData.event;

  refresh(): void {
    this._onDidChangeTreeData.fire();
  }

  getTreeItem(element: vscode.TreeItem): vscode.TreeItem {
    return element;
  }

  async getChildren(element?: vscode.TreeItem): Promise<vscode.TreeItem[]> {
    if (element) {
      if (element.contextValue === 'rag-stats' || element.contextValue === 'prompt-count' || element.contextValue === 'last-hook-run') {
        return [];
      }
      if (element.contextValue === 'last-similar-root') {
        const cursorDir = getWorkspaceCursorDir();
        if (!cursorDir) return [];
        const file = path.join(cursorDir, 'last-similar.json');
        if (!fs.existsSync(file)) {
          return [new vscode.TreeItem('No agent prompt yet', vscode.TreeItemCollapsibleState.None)];
        }
        try {
          const data: LastSimilarPayload = JSON.parse(fs.readFileSync(file, 'utf8'));
          const matches = data.similarMatches || [];
          if (matches.length === 0) {
            return [new vscode.TreeItem('No similar prompts found', vscode.TreeItemCollapsibleState.None)];
          }
          return matches.map((m, i) => {
            const label = (m.promptPreview || `Match ${i + 1}`).slice(0, 60) + (m.similarityScore != null ? ` (${(m.similarityScore * 100).toFixed(0)}%)` : '');
            const item = new vscode.TreeItem(label, vscode.TreeItemCollapsibleState.None);
            item.tooltip = m.responseText ? String(m.responseText).slice(0, 500) : undefined;
            return item;
          });
        } catch {
          return [new vscode.TreeItem('Could not read last-similar.json', vscode.TreeItemCollapsibleState.None)];
        }
      }
      return [];
    }

    const stats = await fetchRagStats();
    const lastRun = readLastRun();
    const root: vscode.TreeItem[] = [];

    const promptCountItem = new vscode.TreeItem('Prompt count', vscode.TreeItemCollapsibleState.None);
    promptCountItem.contextValue = 'prompt-count';
    if (stats != null) {
      const n = getPromptCount(stats);
      promptCountItem.description = n.toLocaleString();
      promptCountItem.tooltip = `Total prompts used so far (agent + dashboard). Every agent prompt increments this.`;
    } else {
      promptCountItem.description = 'offline';
      promptCountItem.tooltip = 'Gateway offline or stats not available.';
    }
    root.push(promptCountItem);

    const statsItem = new vscode.TreeItem('RAG impact', vscode.TreeItemCollapsibleState.None);
    statsItem.contextValue = 'rag-stats';
    if (stats) {
      statsItem.description = `${stats.tokensSavedTotal.toLocaleString()} tokens saved, ${stats.reuseCount} reuses`;
      statsItem.tooltip = `Total: ${stats.tokensSavedTotal.toLocaleString()}\nThis month: ${stats.tokensSavedThisMonth.toLocaleString()}\nReuses: ${stats.reuseCount}`;
    } else {
      statsItem.description = 'offline';
    }
    root.push(statsItem);

    const lastRunItem = new vscode.TreeItem('Last hook run', vscode.TreeItemCollapsibleState.None);
    lastRunItem.contextValue = 'last-hook-run';
    if (lastRun != null && lastRun.timestamp != null) {
      const ago = Math.round((Date.now() - lastRun.timestamp) / 60000);
      const status = lastRun.configFound === false ? 'no config' : lastRun.ingestOk ? (lastRun.similarOk ? 'OK' : 'ingest OK, similar failed') : lastRun.error || 'failed';
      lastRunItem.description = `${ago}m ago · ${status}`;
      lastRunItem.tooltip = `Event: ${lastRun.event ?? '?'}\nConfig: ${lastRun.configFound !== false ? 'yes' : 'no (enable integration)'}\nIngest: ${lastRun.ingestOk ? 'OK' : 'fail'}\nSimilar: ${lastRun.similarOk ? 'OK' : 'fail'}${lastRun.error ? `\nError: ${lastRun.error}` : ''}`;
    } else {
      lastRunItem.description = 'never (or no workspace)';
      lastRunItem.tooltip = 'Hook has not run yet, or .cursor/prompt-similarity-last-run.json not found. Enable integration and send an agent prompt.';
    }
    root.push(lastRunItem);

    const lastSimilarRoot = new vscode.TreeItem('Last similar (from agent)', vscode.TreeItemCollapsibleState.Expanded);
    lastSimilarRoot.contextValue = 'last-similar-root';
    root.push(lastSimilarRoot);

    return root;
  }
}

export function activate(context: vscode.ExtensionContext) {
  context.subscriptions.push(
    vscode.commands.registerCommand('promptSimilarity.ingestPrompt', async () => {
      const editor = vscode.window.activeTextEditor;
      const text = editor ? (editor.selection.isEmpty ? editor.document.getText() : editor.document.getText(editor.selection)) : '';
      if (!text.trim()) {
        vscode.window.showWarningMessage('Select some text or open a document to send as prompt.');
        return;
      }
      try {
        const result = await ingestPrompt(text);
        vscode.window.showInformationMessage(
          result.similarityDetected
            ? `Prompt saved. Similarity detected with ${result.similarUsers.length} other user(s).`
            : `Prompt saved. ID: ${result.promptId}`
        );
      } catch (e: unknown) {
        vscode.window.showErrorMessage(`Prompt Similarity: ${e instanceof Error ? e.message : String(e)}`);
      }
    })
  );

  context.subscriptions.push(
    vscode.commands.registerCommand('promptSimilarity.findSimilar', async () => {
      const editor = vscode.window.activeTextEditor;
      const text = editor ? (editor.selection.isEmpty ? editor.document.getText() : editor.document.getText(editor.selection)) : '';
      const query = text.trim() || (await vscode.window.showInputBox({ prompt: 'Enter text to find similar prompts' }) || '').trim();
      if (!query) return;
      try {
        const results = await findSimilar(query);
        if (results.length === 0) {
          vscode.window.showInformationMessage('No similar prompts found.');
          return;
        }
        vscode.window.showInformationMessage(`Found ${results.length} similar prompt(s). Score: ${results[0].score?.toFixed(2)}`);
      } catch (e: unknown) {
        vscode.window.showErrorMessage(`Prompt Similarity: ${e instanceof Error ? e.message : String(e)}`);
      }
    })
  );

  context.subscriptions.push(
    vscode.commands.registerCommand('promptSimilarity.enableIntegration', () => enableIntegration(context))
  );
  context.subscriptions.push(
    vscode.commands.registerCommand('promptSimilarity.disableIntegration', () => disableIntegration())
  );

  statusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
  context.subscriptions.push(statusBarItem);
  statusBarItem.show();
  updateStatusBar();
  const statsInterval = setInterval(updateStatusBar, 60_000);
  context.subscriptions.push({ dispose: () => clearInterval(statsInterval) });

  const treeProvider = new PromptSimilarityTreeProvider();
  context.subscriptions.push(
    vscode.window.registerTreeDataProvider('promptSimilarityView', treeProvider)
  );
  context.subscriptions.push(
    vscode.commands.registerCommand('promptSimilarity.refreshView', () => {
      treeProvider.refresh();
      updateStatusBar();
    })
  );
  context.subscriptions.push(
    vscode.commands.registerCommand('promptSimilarity.openLiveSimilar', () => openLiveSimilarPanel(context, treeProvider))
  );

  const cursorDir = getWorkspaceCursorDir();
  if (cursorDir) {
    const lastSimilarWatcher = vscode.workspace.createFileSystemWatcher(path.join(cursorDir, 'last-similar.json'));
    lastSimilarWatcher.onDidChange(() => treeProvider.refresh());
    lastSimilarWatcher.onDidCreate(() => treeProvider.refresh());
    context.subscriptions.push(lastSimilarWatcher);
    const lastRunWatcher = vscode.workspace.createFileSystemWatcher(path.join(cursorDir, 'prompt-similarity-last-run.json'));
    lastRunWatcher.onDidChange(() => { treeProvider.refresh(); updateStatusBar(); });
    lastRunWatcher.onDidCreate(() => { treeProvider.refresh(); updateStatusBar(); });
    context.subscriptions.push(lastRunWatcher);
  }
}

export function deactivate() {}
