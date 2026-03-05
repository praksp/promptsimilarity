import * as vscode from 'vscode';

const DEFAULT_GATEWAY = 'http://localhost:8080';

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
  return res.json();
}

async function findSimilar(text: string, topK = 10): Promise<Array<{ promptId: string; userId: string; score: number; textPreview: string }>> {
  const { gatewayUrl, orgId, userId } = getConfig();
  const params = new URLSearchParams({ text, orgId, userId, topK: String(topK), minScore: '0.7' });
  const res = await fetch(`${gatewayUrl}/api/v1/prompts/similar?${params}`);
  if (!res.ok) throw new Error(`Find similar failed: ${res.status}`);
  return res.json();
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
}

export function deactivate() {}
