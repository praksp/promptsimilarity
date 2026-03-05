package com.promptsimilarity.promptservice.llm;

import io.smallrye.mutiny.Uni;

/**
 * Pluggable LLM provider for RAG. Local (Ollama) first; other backends can be added later.
 */
public interface LlmProvider {

    /**
     * Complete a prompt, optionally with context (e.g. similar past Q&amp;A).
     * Returns response text and token counts for impact metrics.
     */
    Uni<LlmResult> complete(String prompt, String context);
}
