# Prompt Similarity – Cursor / VS Code extension

Sends the current selection (or full document) to your local Prompt Similarity gateway. When the service detects high similarity with other users, you get a notification.

## Configuration

- **promptSimilarity.gatewayUrl**: API gateway URL (default: `http://localhost:8080`)
- **promptSimilarity.orgId**: Organization ID
- **promptSimilarity.userId**: Your user ID (optional; falls back to hostname)

## Commands

- **Send current selection to Prompt Similarity**: Ingest the selected text (or full document) to the local service.
- **Find similar prompts**: Run a similarity search for the current selection or entered text.

## Install in container

This package can be installed in a dev container by adding it as a VS Code extension or by running `npm pack` and installing the resulting `.vsix` in Cursor/VS Code.
