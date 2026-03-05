package com.promptsimilarity.promptservice.rag;

import com.promptsimilarity.promptservice.EmbeddingClient;
import com.promptsimilarity.promptservice.GraphServiceClient;
import com.promptsimilarity.promptservice.PromptRepository;
import com.promptsimilarity.promptservice.VectorServiceClient;
import com.promptsimilarity.promptservice.llm.OllamaLlmClient;
import com.promptsimilarity.proto.prompt.v1.IngestPromptRequest;
import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * RAG flow: retrieve similar prompt-response pairs; if match above threshold return cached response
 * and ask for satisfaction; if satisfied count tokens saved. Else call LLM, store response, vector + graph.
 */
@ApplicationScoped
public class RagOrchestrator {

    @ConfigProperty(name = "rag.similarity.threshold", defaultValue = "0.65")
    double ragSimilarityThreshold;

    @Inject
    EmbeddingClient embeddingClient;

    @Inject
    VectorServiceClient vectorClient;

    @Inject
    GraphServiceClient graphClient;

    @Inject
    ResponseStore responseStore;

    @Inject
    TokensSavedStore tokensSavedStore;

    @Inject
    OllamaLlmClient llmClient;

    @Inject
    PromptRepository promptRepository;

    /**
     * Ask: embed prompt, search similar prompts; if one has a stored response with score >= threshold,
     * return it (fromCache). Else call LLM, store prompt+response, vector+graph, return new response.
     */
    public Uni<RagAskResult> ask(String prompt, String userId, String orgId) {
        if (prompt == null || prompt.isBlank()) {
            return Uni.createFrom().item(new RagAskResult("", null, null, 0, 0, false, false));
        }
        String safeUser = userId != null ? userId : "";
        String safeOrg = orgId != null ? orgId : "default-org";

        return embeddingClient.embed(prompt)
                .flatMap(embedding -> vectorClient.searchSimilar(
                                embedding, safeOrg, null, 5, ragSimilarityThreshold)
                        .onFailure().recoverWithItem(() -> List.of())
                        .flatMap(matches -> {
                            for (VectorServiceClient.VectorMatch m : matches) {
                                StoredResponse cached = responseStore.getByPromptId(m.getPromptId());
                                if (cached != null) {
                                    return Uni.createFrom().item(new RagAskResult(
                                            cached.text(),
                                            cached.responseId(),
                                            cached.promptId(),
                                            cached.tokensUsed(),
                                            m.score(),
                                            true,
                                            true));
                                }
                            }
                            return callLlmAndStore(prompt, safeUser, safeOrg, embedding);
                        }));
    }

    private Uni<RagAskResult> callLlmAndStore(String prompt, String userId, String orgId, float[] promptEmbedding) {
        String promptId = UUID.randomUUID().toString();
        IngestPromptRequest req = IngestPromptRequest.newBuilder()
                .setUserId(userId)
                .setOrgId(orgId)
                .setText(prompt)
                .setLanguage("en")
                .build();

        return llmClient.complete(prompt, null)
                .flatMap(llmResult -> {
                    // Don't cache error messages (e.g. "model not found", "not reachable") so next ask retries the LLM
                    boolean isErrorResponse = isLlmErrorResponse(llmResult.text());
                    if (isErrorResponse) {
                        return Uni.createFrom().item(new RagAskResult(
                                llmResult.text(),
                                null,
                                null,
                                0,
                                0,
                                false,
                                true));
                    }
                    StoredResponse stored = responseStore.save(promptId, userId, orgId, llmResult.text(), llmResult.totalTokens());
                    long now = System.currentTimeMillis();
                    promptRepository.save(promptId, req);

                    Uni<Void> storePrompt = vectorClient.storePrompt(promptId, req, promptEmbedding).onFailure().recoverWithItem(() -> null);
                    Uni<Void> storeReasoning = embeddingClient.embed(llmResult.text())
                            .flatMap(respEmb -> vectorClient.storeReasoning(
                                    stored.responseId(), promptId, userId, orgId, respEmb,
                                    llmResult.text().length() > 500 ? llmResult.text().substring(0, 500) : llmResult.text()))
                            .onFailure().recoverWithItem(() -> null);
                    Uni<Void> graphPrompt = graphClient.recordPrompt(promptId, req).onFailure().recoverWithItem(() -> null);
                    Uni<Void> graphResponse = graphClient.recordResponse(promptId, stored.responseId(), userId, orgId, llmResult.totalTokens(), now).onFailure().recoverWithItem(() -> null);

                    return Uni.combine().all().unis(storePrompt, storeReasoning, graphPrompt, graphResponse).discardItems()
                            .replaceWith(new RagAskResult(
                                    llmResult.text(),
                                    stored.responseId(),
                                    promptId,
                                    llmResult.totalTokens(),
                                    0,
                                    false,
                                    true));
                });
    }

    /** Detect LLM error messages so we don't store them as valid responses. */
    private static boolean isLlmErrorResponse(String text) {
        if (text == null || text.isBlank()) return true;
        String lower = text.toLowerCase();
        return lower.contains("not reachable") || lower.contains("not available") || lower.contains("pull it with")
                || lower.contains("ollama not reachable") || lower.contains("model ") && lower.contains("not found")
                || lower.contains("request failed:");
    }

    /**
     * Find similar prompts that already have LLM responses. Used to suggest existing answers before calling the LLM.
     */
    public Uni<List<SimilarResponseMatch>> similarResponses(String prompt, String userId, String orgId) {
        if (prompt == null || prompt.isBlank()) {
            return Uni.createFrom().item(List.of());
        }
        String safeOrg = orgId != null ? orgId : "default-org";
        return embeddingClient.embed(prompt)
                .flatMap(embedding -> vectorClient.searchSimilar(
                                embedding, safeOrg, null, 10, ragSimilarityThreshold)
                        .onFailure().recoverWithItem(() -> List.of()))
                .map(matches -> {
                    List<SimilarResponseMatch> out = new ArrayList<>();
                    for (VectorServiceClient.VectorMatch m : matches) {
                        StoredResponse stored = responseStore.getByPromptId(m.getPromptId());
                        if (stored != null) {
                            out.add(new SimilarResponseMatch(
                                    m.getPromptId(),
                                    stored.responseId(),
                                    m.getTextPreview() != null ? m.getTextPreview() : "",
                                    stored.text(),
                                    m.score(),
                                    stored.tokensUsed()));
                        }
                    }
                    return out;
                });
    }

    /**
     * Call LLM and store response; optionally record similarity to previously found prompts.
     */
    public Uni<RagAskResult> askLlm(String prompt, String userId, String orgId, List<SimilarMatch> similarMatches) {
        if (prompt == null || prompt.isBlank()) {
            return Uni.createFrom().item(new RagAskResult("", null, null, 0, 0, false, false));
        }
        String safeUser = userId != null ? userId : "";
        String safeOrg = orgId != null ? orgId : "default-org";
        return embeddingClient.embed(prompt)
                .flatMap(embedding -> callLlmAndStore(prompt, safeUser, safeOrg, embedding)
                        .flatMap(result -> {
                            if ((similarMatches == null || similarMatches.isEmpty()) || result.promptId() == null) {
                                return Uni.createFrom().item(result);
                            }
                            List<Uni<Void>> unis = similarMatches.stream()
                                    .map(sm -> graphClient.recordSimilarity(result.promptId(), sm.promptId(), sm.score()).onFailure().recoverWithItem(() -> null))
                                    .toList();
                            return Uni.combine().all().unis(unis).discardItems().replaceWith(result);
                        }));
    }

    /**
     * Record prompt in vector+graph and link it as similar to an existing prompt (when user chose "Use this answer").
     */
    public Uni<String> recordPromptAndSimilarity(String promptText, String userId, String orgId, String similarToPromptId, double similarityScore) {
        if (promptText == null || promptText.isBlank()) {
            return Uni.createFrom().item(null);
        }
        String promptId = UUID.randomUUID().toString();
        String safeUser = userId != null ? userId : "";
        String safeOrg = orgId != null ? orgId : "default-org";
        IngestPromptRequest req = IngestPromptRequest.newBuilder()
                .setUserId(safeUser)
                .setOrgId(safeOrg)
                .setText(promptText)
                .setLanguage("en")
                .build();
        promptRepository.save(promptId, req);
        return embeddingClient.embed(promptText)
                .flatMap(embedding -> {
                    Uni<Void> storePrompt = vectorClient.storePrompt(promptId, req, embedding).onFailure().recoverWithItem(() -> null);
                    Uni<Void> graphPrompt = graphClient.recordPrompt(promptId, req).onFailure().recoverWithItem(() -> null);
                    Uni<Void> graphSimilar = graphClient.recordSimilarity(promptId, similarToPromptId, similarityScore).onFailure().recoverWithItem(() -> null);
                    return Uni.combine().all().unis(storePrompt, graphPrompt, graphSimilar).discardItems().replaceWith(promptId);
                });
    }

    /**
     * When user indicates satisfaction with a cached response, record tokens saved.
     */
    public Uni<Void> feedback(String responseId, boolean satisfied, String orgId) {
        if (!satisfied) {
            return Uni.createFrom().voidItem();
        }
        StoredResponse r = responseStore.getByResponseId(responseId);
        if (r != null) {
            tokensSavedStore.addSaved(r.tokensUsed(), orgId != null ? orgId : "default-org");
        }
        return Uni.createFrom().voidItem();
    }

    /**
     * Stats for UI: total tokens saved, this month, reuse count.
     */
    public RagStats getStats(String orgId) {
        String safeOrg = orgId != null ? orgId : "default-org";
        return new RagStats(
                tokensSavedStore.getTotalSaved(),
                tokensSavedStore.getSavedThisMonth(),
                tokensSavedStore.getSavedForOrg(safeOrg),
                tokensSavedStore.getReuseCount());
    }

    public record RagAskResult(
            String responseText,
            String responseId,
            String promptId,
            long tokensUsed,
            double similarityScore,
            boolean fromCache,
            boolean askSatisfaction
    ) {}

    public record RagStats(long tokensSavedTotal, long tokensSavedThisMonth, long tokensSavedOrg, long reuseCount) {}

    /** One similar prompt that has a stored LLM response (for UI to show and let user choose). */
    public record SimilarResponseMatch(String promptId, String responseId, String promptPreview, String responseText, double similarityScore, long tokensUsed) {}

    /** Prompt id + score for recording similarity after a new LLM response. */
    public record SimilarMatch(String promptId, double score) {}
}
