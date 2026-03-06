/**
 * Unit tests for the prompt-similarity hook script.
 * Runs the hook as a subprocess with mocked HTTP and temp workspace.
 */

const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const http = require('http');
const os = require('os');

const HOOK_SCRIPT = path.join(__dirname, '..', 'scripts', 'prompt-similarity-hook.js');

function runHook(stdinJson) {
  return new Promise((resolve, reject) => {
    const proc = spawn('node', [HOOK_SCRIPT], { stdio: ['pipe', 'pipe', 'pipe'] });
    let stdout = '';
    let stderr = '';
    proc.stdout.on('data', (d) => { stdout += d.toString(); });
    proc.stderr.on('data', (d) => { stderr += d.toString(); });
    proc.on('error', reject);
    proc.on('close', (code) => resolve({ code, stdout, stderr }));
    proc.stdin.write(JSON.stringify(stdinJson));
    proc.stdin.end();
  });
}

function createTempWorkspaceWithConfig(gatewayUrl) {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'ps-hook-test-'));
  const cursorDir = path.join(tmp, '.cursor');
  fs.mkdirSync(cursorDir, { recursive: true });
  fs.writeFileSync(
    path.join(cursorDir, 'prompt-similarity.json'),
    JSON.stringify({ gatewayUrl, userId: 'test-user', orgId: 'default-org' }, null, 2)
  );
  return tmp;
}

describe('Hook script', () => {
  let server;
  let serverPort;
  let tempDir;

  beforeAll((done) => {
    server = http.createServer((req, res) => {
      if (req.method === 'POST' && req.url === '/api/v1/prompts/ingest') {
        let body = '';
        req.on('data', (c) => { body += c; });
        req.on('end', () => {
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ promptId: 'test-prompt-id', similarityDetected: false, similarUsers: [] }));
        });
        return;
      }
      if (req.method === 'POST' && req.url === '/api/v1/prompts/rag/similar-responses') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify([]));
        return;
      }
      res.writeHead(404);
      res.end();
    });
    server.listen(0, () => {
      serverPort = server.address().port;
      tempDir = createTempWorkspaceWithConfig(`http://127.0.0.1:${serverPort}`);
      done();
    });
  });

  afterAll((done) => {
    if (server) server.close(done);
    if (tempDir && fs.existsSync(tempDir)) {
      try {
        fs.rmSync(tempDir, { recursive: true });
      } catch (_) {}
    }
  });

  test('exits with continue: true when config found and backend OK', async () => {
    const payload = {
      hook_event_name: 'beforeSubmitPrompt',
      workspace_roots: [tempDir],
      prompt: 'What is the weather?',
      conversation_id: 'conv-1',
      generation_id: 'gen-1',
    };
    const { code, stdout } = await runHook(payload);
    expect(code).toBe(0);
    const out = JSON.parse(stdout.trim());
    expect(out.continue).toBe(true);
  });

  test('writes last-run file with ingestOk and similarOk when backend OK', async () => {
    const payload = {
      hook_event_name: 'beforeSubmitPrompt',
      workspace_roots: [tempDir],
      prompt: 'Hello world',
      conversation_id: 'c2',
      generation_id: 'g2',
    };
    await runHook(payload);
    const lastRunPath = path.join(tempDir, '.cursor', 'prompt-similarity-last-run.json');
    expect(fs.existsSync(lastRunPath)).toBe(true);
    const lastRun = JSON.parse(fs.readFileSync(lastRunPath, 'utf8'));
    expect(lastRun.configFound).toBe(true);
    expect(lastRun.ingestOk).toBe(true);
    expect(lastRun.similarOk).toBe(true);
    expect(lastRun.event).toBe('beforeSubmitPrompt');
    expect(lastRun.timestamp).toBeDefined();
  });

  test('writes last-run with configFound false when no config in workspace', async () => {
    const emptyDir = fs.mkdtempSync(path.join(os.tmpdir(), 'ps-hook-empty-'));
    const payload = {
      hook_event_name: 'beforeSubmitPrompt',
      workspace_roots: [emptyDir],
      prompt: 'Hi',
    };
    const { code, stdout } = await runHook(payload);
    expect(code).toBe(0);
    const out = JSON.parse(stdout.trim());
    expect(out.continue).toBe(true);
    const lastRunPath = path.join(emptyDir, '.cursor', 'prompt-similarity-last-run.json');
    expect(fs.existsSync(lastRunPath)).toBe(true);
    const lastRun = JSON.parse(fs.readFileSync(lastRunPath, 'utf8'));
    expect(lastRun.configFound).toBe(false);
    try { fs.rmSync(emptyDir, { recursive: true }); } catch (_) {}
  });

  test('returns continue false and user_message when similar-responses returns a match >= 0.5', async () => {
    const serverWithCache = http.createServer((req, res) => {
      if (req.method === 'POST' && req.url === '/api/v1/prompts/rag/similar-responses') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify([{
          promptId: 'p1',
          responseId: 'r1',
          promptPreview: 'whats the weather',
          responseText: 'Seattle is 55°F and cloudy.',
          similarityScore: 0.85,
          tokensUsed: 0,
        }]));
        return;
      }
      res.writeHead(404);
      res.end();
    });
    await new Promise((resolve) => serverWithCache.listen(0, () => resolve()));
    const port = serverWithCache.address().port;
    const dir = createTempWorkspaceWithConfig(`http://127.0.0.1:${port}`);
    const payload = {
      hook_event_name: 'beforeSubmitPrompt',
      workspace_roots: [dir],
      prompt: 'whats the weather',
      conversation_id: 'c-sc',
      generation_id: 'g-sc',
    };
    const { code, stdout } = await runHook(payload);
    serverWithCache.close();
    try { fs.rmSync(dir, { recursive: true }); } catch (_) {}
    expect(code).toBe(0);
    const out = JSON.parse(stdout.trim());
    expect(out.continue).toBe(false);
    expect(out.user_message).toBe('Seattle is 55°F and cloudy.');
  });

  test('writes last-similar.json when beforeSubmitPrompt runs', async () => {
    const payload = {
      hook_event_name: 'beforeSubmitPrompt',
      workspace_roots: [tempDir],
      prompt: 'Test prompt for similar',
      conversation_id: 'c3',
      generation_id: 'g3',
    };
    await runHook(payload);
    const lastSimilarPath = path.join(tempDir, '.cursor', 'last-similar.json');
    expect(fs.existsSync(lastSimilarPath)).toBe(true);
    const data = JSON.parse(fs.readFileSync(lastSimilarPath, 'utf8'));
    expect(data.promptPreview).toBeDefined();
    expect(data.timestamp).toBeDefined();
    expect(Array.isArray(data.similarMatches)).toBe(true);
  });
});
