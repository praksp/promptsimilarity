#!/usr/bin/env node
/**
 * Cursor hook: runs on beforeSubmitPrompt (and optionally stop/afterAgentResponse).
 * Reads config from workspace .cursor/prompt-similarity.json, calls Prompt Similarity
 * gateway (ingest + similar-responses), writes last-similar.json for the extension.
 * Always returns {"continue": true} so the user's prompt is never blocked.
 */

const fs = require('fs');
const path = require('path');

const TIMEOUT_MS = 8000;

function readStdin() {
  return new Promise((resolve) => {
    let data = '';
    process.stdin.setEncoding('utf8');
    process.stdin.on('data', (chunk) => { data += chunk; });
    process.stdin.on('end', () => resolve(data));
  });
}

const LAST_RUN_FILE = 'prompt-similarity-last-run.json';

function loadConfig(workspaceRoot) {
  if (!workspaceRoot) return null;
  const configPath = path.join(workspaceRoot, '.cursor', 'prompt-similarity.json');
  try {
    const raw = fs.readFileSync(configPath, 'utf8');
    const cfg = JSON.parse(raw);
    return {
      gatewayUrl: (cfg.gatewayUrl || 'http://localhost:8080').replace(/\/$/, ''),
      userId: cfg.userId || 'anonymous',
      orgId: cfg.orgId || 'default-org',
    };
  } catch {
    return null;
  }
}

/** Try multiple workspace roots and cwd to find config; return { config, workspaceRoot } or { config: null, workspaceRoot }. */
function findConfigAndWorkspace(payload) {
  const roots = payload.workspace_roots || [];
  const tried = [...roots];
  if (process.cwd() && !tried.includes(process.cwd())) tried.push(process.cwd());
  for (const root of tried) {
    if (!root || typeof root !== 'string') continue;
    const config = loadConfig(root);
    if (config) return { config, workspaceRoot: root };
  }
  return { config: null, workspaceRoot: tried[0] || process.cwd() || '' };
}

function writeLastRun(workspaceRoot, data) {
  if (!workspaceRoot) return;
  const dir = path.join(workspaceRoot, '.cursor');
  const file = path.join(dir, LAST_RUN_FILE);
  try {
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(file, JSON.stringify({ ...data, timestamp: Date.now() }, null, 2), 'utf8');
  } catch (e) {
    console.error('[prompt-similarity-hook] writeLastRun:', e.message);
  }
}

function writeLastSimilar(workspaceRoot, payload) {
  const dir = path.join(workspaceRoot, '.cursor');
  const file = path.join(dir, 'last-similar.json');
  try {
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(file, JSON.stringify(payload, null, 2), 'utf8');
  } catch (e) {
    console.error('[prompt-similarity-hook] writeLastSimilar:', e.message);
  }
}

function writePendingCursorResponse(workspaceRoot, payload) {
  const dir = path.join(workspaceRoot, '.cursor');
  const file = path.join(dir, 'pending-cursor-response.json');
  try {
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(file, JSON.stringify(payload, null, 2), 'utf8');
  } catch (e) {
    console.error('[prompt-similarity-hook] writePendingCursorResponse:', e.message);
  }
}

function readAndClearPendingCursorResponse(workspaceRoot, conversationId, generationId) {
  const file = path.join(workspaceRoot, '.cursor', 'pending-cursor-response.json');
  try {
    if (!fs.existsSync(file)) return null;
    const raw = fs.readFileSync(file, 'utf8');
    const data = JSON.parse(raw);
    if (data.conversationId === conversationId && data.generationId === generationId) {
      try { fs.unlinkSync(file); } catch {}
      return data.promptId || null;
    }
    return null;
  } catch {
    return null;
  }
}

/** Cursor: continue false blocks the agent; user_message is shown to the user (e.g. cached response). */
function respond(continuePrompt = true, userMessage = null) {
  const out = { continue: continuePrompt };
  if (userMessage != null && typeof userMessage === 'string') out.user_message = userMessage;
  process.stdout.write(JSON.stringify(out) + '\n');
}

async function fetchWithTimeout(url, options, ms = TIMEOUT_MS) {
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), ms);
  try {
    const res = await fetch(url, { ...options, signal: ctrl.signal });
    clearTimeout(t);
    return res;
  } catch (e) {
    clearTimeout(t);
    throw e;
  }
}

// Minimum similarity score (0–1) to use cached response and skip the agent. Backend may use 0.65; we short-circuit when match >= this.
const SHORT_CIRCUIT_THRESHOLD = 0.5;

async function handleBeforeSubmitPrompt(payload, config, workspaceRoot) {
  const prompt = payload.prompt || '';
  let ingestOk = false;
  let similarOk = false;
  let error = null;

  if (!prompt.trim()) {
    writeLastRun(workspaceRoot, { event: 'beforeSubmitPrompt', configFound: true, ingestOk: false, similarOk: false, error: 'empty_prompt', workspaceRoot });
    return;
  }

  const base = config.gatewayUrl;
  const ingestUrl = `${base}/api/v1/prompts/ingest`;
  const similarUrl = `${base}/api/v1/prompts/rag/similar-responses`;
  const body = {
    userId: config.userId,
    orgId: config.orgId,
    prompt: prompt.trim(),
  };

  // 1) Call similar-responses FIRST (realtime similarity like dashboard). If we have a cached match above threshold, short-circuit: block agent and show cached response.
  let similarMatches = [];
  try {
    const res = await fetchWithTimeout(similarUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (res.ok) {
      const data = await res.json();
      similarMatches = Array.isArray(data) ? data : [];
      similarOk = true;
    } else {
      error = `similar ${res.status}`;
    }
  } catch (e) {
    console.error('[prompt-similarity-hook] similar-responses failed:', e.message);
    error = e.message || 'similar_failed';
  }

  const bestMatch = similarMatches[0];
  const score = bestMatch && (typeof bestMatch.similarityScore === 'number' ? bestMatch.similarityScore : bestMatch.similarity_score);
  const cachedText = bestMatch && (bestMatch.responseText != null ? bestMatch.responseText : bestMatch.response_text);
  const responseId = bestMatch && (bestMatch.responseId != null ? bestMatch.responseId : bestMatch.response_id);
  const promptIdMatch = bestMatch && (bestMatch.promptId != null ? bestMatch.promptId : bestMatch.prompt_id);
  if (bestMatch && typeof score === 'number' && score >= SHORT_CIRCUIT_THRESHOLD && cachedText && typeof cachedText === 'string' && cachedText.trim()) {
    // Update backend metrics (tokens saved, reuses) when we short-circuit
    const feedbackUrl = `${base}/api/v1/prompts/rag/feedback`;
    const recordUrl = `${base}/api/v1/prompts/rag/record-satisfied`;
    if (responseId && typeof responseId === 'string' && responseId.trim()) {
      try {
        await fetchWithTimeout(feedbackUrl, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ responseId: responseId.trim(), satisfied: true, orgId: config.orgId }),
        });
      } catch (e) {
        console.error('[prompt-similarity-hook] rag/feedback failed:', e.message);
      }
    }
    if (promptIdMatch && typeof promptIdMatch === 'string' && promptIdMatch.trim()) {
      try {
        await fetchWithTimeout(recordUrl, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            promptText: prompt.trim(),
            userId: config.userId,
            orgId: config.orgId,
            similarToPromptId: promptIdMatch.trim(),
            similarityScore: score,
          }),
        });
      } catch (e) {
        console.error('[prompt-similarity-hook] rag/record-satisfied failed:', e.message);
      }
    }
    writeLastSimilar(workspaceRoot, {
      promptPreview: prompt.trim().slice(0, 200),
      conversationId: payload.conversation_id,
      generationId: payload.generation_id,
      timestamp: Date.now(),
      similarMatches,
      shortCircuited: true,
      usedScore: score,
    });
    writeLastRun(workspaceRoot, { event: 'beforeSubmitPrompt', configFound: true, ingestOk: false, similarOk: true, error: null, shortCircuited: true, workspaceRoot });
    respond(false, cachedText.trim());
    return;
  }

  // 2) No cache hit above threshold: ingest (so prompt is stored) then let the agent run.
  let ingestPromptId = null;
  try {
    const ingestRes = await fetchWithTimeout(ingestUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ...body, text: prompt.trim(), language: 'en' }),
    });
    if (ingestRes.ok) {
      const ingestData = await ingestRes.json();
      ingestPromptId = ingestData.promptId || null;
      ingestOk = true;
    } else {
      error = error || `ingest ${ingestRes.status}`;
    }
  } catch (e) {
    console.error('[prompt-similarity-hook] ingest failed:', e.message);
    error = error || e.message || 'ingest_failed';
  }

  if (ingestPromptId && payload.conversation_id != null && payload.generation_id != null) {
    writePendingCursorResponse(workspaceRoot, {
      conversationId: payload.conversation_id,
      generationId: payload.generation_id,
      promptId: ingestPromptId,
    });
  }

  writeLastSimilar(workspaceRoot, {
    promptPreview: prompt.trim().slice(0, 200),
    conversationId: payload.conversation_id,
    generationId: payload.generation_id,
    timestamp: Date.now(),
    similarMatches,
  });

  writeLastRun(workspaceRoot, { event: 'beforeSubmitPrompt', configFound: true, ingestOk, similarOk, error, workspaceRoot });
  respond(true);
}

async function handleStopOrAfterAgentResponse(payload, config, workspaceRoot) {
  const base = config.gatewayUrl;
  const responseText = payload.response || payload.response_text || payload.text || '';
  if (!responseText) return;

  const conversationId = payload.conversation_id;
  const generationId = payload.generation_id;
  const promptId = workspaceRoot
    ? readAndClearPendingCursorResponse(workspaceRoot, conversationId, generationId)
    : null;

  const cursorReportUrl = `${base}/api/v1/prompts/cursor-response`;
  try {
    await fetchWithTimeout(cursorReportUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        userId: config.userId,
        orgId: config.orgId,
        conversationId,
        generationId,
        prompt: payload.prompt || '',
        responseText: responseText.slice(0, 10000),
        promptId: promptId || undefined,
      }),
    });
  } catch {
    // Endpoint may not exist; ignore
  }
}

async function main() {
  let input;
  let payload;
  let workspaceRoot = process.cwd();

  try {
    input = await readStdin();
  } catch (e) {
    respond(true);
    process.exit(0);
  }

  try {
    payload = JSON.parse(input.trim());
  } catch {
    respond(true);
    process.exit(0);
  }

  const eventName = payload.hook_event_name || payload.event;
  const { config, workspaceRoot: root } = findConfigAndWorkspace(payload);
  workspaceRoot = root;

  if (!config) {
    writeLastRun(workspaceRoot, { event: eventName, configFound: false, ingestOk: false, similarOk: false, error: 'no_config', workspaceRoot });
    respond(true);
    process.exit(0);
  }

  if (eventName === 'beforeSubmitPrompt') {
    await handleBeforeSubmitPrompt(payload, config, workspaceRoot);
    // Handler already wrote continue true/false (+ optional user_message)
  } else if (eventName === 'stop' || eventName === 'afterAgentResponse') {
    await handleStopOrAfterAgentResponse(payload, config, workspaceRoot);
    respond(true);
  } else {
    respond(true);
  }
  process.exit(0);
}

main().catch(() => {
  respond(true);
  process.exit(0);
});
