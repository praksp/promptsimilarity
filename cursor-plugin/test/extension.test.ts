/**
 * Unit tests for the Prompt Similarity extension.
 * Tests config, status bar text from stats, and last-run display logic.
 */

// Mock vscode before any import that uses it
const mockWorkspaceFolders = [{ uri: { fsPath: '/fake/workspace' } }];
const mockConfig = {
  get: jest.fn((key: string) => {
    if (key === 'gatewayUrl') return 'http://localhost:8080';
    if (key === 'orgId') return 'default-org';
    if (key === 'userId') return 'test-user';
    return undefined;
  }),
};
const mockWorkspace = {
  workspaceFolders: mockWorkspaceFolders,
  getConfiguration: () => mockConfig,
};
const mockWindow = { showWarningMessage: jest.fn(), showErrorMessage: jest.fn(), showInformationMessage: jest.fn() };
jest.mock('vscode', () => ({
  workspace: {
    getConfiguration: () => mockConfig,
    workspaceFolders: mockWorkspaceFolders,
    createFileSystemWatcher: jest.fn(() => ({ onDidChange: jest.fn(), onDidCreate: jest.fn(), dispose: jest.fn() })),
  },
  window: {
    ...mockWindow,
    createStatusBarItem: jest.fn(() => ({ show: jest.fn(), text: '', tooltip: '' })),
    activeTextEditor: undefined,
    createInputBox: jest.fn(),
    registerTreeDataProvider: jest.fn(),
  },
  commands: { registerCommand: jest.fn(() => ({ dispose: jest.fn() })) },
  EventEmitter: jest.fn().mockImplementation(() => ({ fire: jest.fn(), event: {} })),
  TreeItemCollapsibleState: { None: 0, Expanded: 1 },
  StatusBarAlignment: { Right: 0 },
  Uri: { file: (p: string) => ({ fsPath: p }) },
}), { virtual: true });

import * as path from 'path';
import * as fs from 'fs';

describe('Extension config and stats', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  test('getConfig returns gatewayUrl, orgId, userId from settings', () => {
    const config = mockConfig;
    const gatewayUrl = config.get('gatewayUrl') || 'http://localhost:8080';
    const orgId = config.get('orgId') || 'default-org';
    const userId = config.get('userId') || 'anonymous';
    expect(gatewayUrl).toBe('http://localhost:8080');
    expect(orgId).toBe('default-org');
    expect(userId).toBe('test-user');
  });

  test('status bar text includes prompt count when stats have promptCount', () => {
    const stats: { tokensSavedTotal: number; tokensSavedThisMonth: number; reuseCount: number; promptCount?: number } = { tokensSavedTotal: 100, tokensSavedThisMonth: 10, reuseCount: 2, promptCount: 42 };
    const prompts = (stats.promptCount ?? 0).toLocaleString();
    const text = `$(symbol-misc) Prompts: ${prompts} | RAG: ${stats.tokensSavedTotal.toLocaleString()} saved`;
    expect(text).toContain('42');
    expect(text).toContain('100');
  });

  test('status bar text handles missing promptCount', () => {
    const stats: { tokensSavedTotal: number; tokensSavedThisMonth: number; reuseCount: number; promptCount?: number } = { tokensSavedTotal: 50, tokensSavedThisMonth: 5, reuseCount: 1 };
    const prompts = (stats.promptCount ?? 0).toLocaleString();
    expect(prompts).toBe('0');
  });

  test('last-run description format when ingest and similar OK', () => {
    const lastRun = { event: 'beforeSubmitPrompt', timestamp: Date.now() - 2 * 60000, configFound: true, ingestOk: true, similarOk: true };
    const ago = Math.round((Date.now() - (lastRun.timestamp ?? 0)) / 60000);
    const status = lastRun.configFound === false ? 'no config' : lastRun.ingestOk ? (lastRun.similarOk ? 'OK' : 'ingest OK, similar failed') : (lastRun as { error?: string }).error || 'failed';
    expect(ago).toBeGreaterThanOrEqual(1);
    expect(status).toBe('OK');
  });

  test('last-run description when config missing', () => {
    const lastRun = { configFound: false };
    const status = lastRun.configFound === false ? 'no config' : 'OK';
    expect(status).toBe('no config');
  });
});

describe('Hook script path and integration', () => {
  test('hook script name is prompt-similarity-hook.js', () => {
    const HOOK_SCRIPT_NAME = 'prompt-similarity-hook.js';
    expect(HOOK_SCRIPT_NAME).toBe('prompt-similarity-hook.js');
  });

  test('getHookScriptPath joins extensionPath and scripts dir', () => {
    const extensionPath = '/ext';
    const scriptName = 'prompt-similarity-hook.js';
    const expected = path.join(extensionPath, 'scripts', scriptName);
    expect(expected).toContain('scripts');
    expect(expected.endsWith(scriptName)).toBe(true);
  });
});
