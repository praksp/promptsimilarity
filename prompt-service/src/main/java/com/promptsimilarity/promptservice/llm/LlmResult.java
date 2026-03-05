package com.promptsimilarity.promptservice.llm;

/**
 * Result of an LLM completion with token usage for RAG impact tracking.
 */
public record LlmResult(
    String text,
    long inputTokens,
    long outputTokens
) {
    public long totalTokens() {
        return inputTokens + outputTokens;
    }
}
