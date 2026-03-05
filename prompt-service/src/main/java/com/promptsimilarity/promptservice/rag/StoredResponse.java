package com.promptsimilarity.promptservice.rag;

/**
 * A stored LLM response linked to a prompt for RAG reuse.
 */
public record StoredResponse(
    String responseId,
    String promptId,
    String userId,
    String orgId,
    String text,
    long tokensUsed,
    long createdAt
) {}
