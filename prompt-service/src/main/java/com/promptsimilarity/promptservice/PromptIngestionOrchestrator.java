package com.promptsimilarity.promptservice;

import com.promptsimilarity.proto.prompt.v1.FindSimilarPromptsRequest;
import com.promptsimilarity.proto.prompt.v1.IngestPromptRequest;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Orchestrates: embed → vector store + search index + feature store → similarity check → notification if threshold met.
 */
@ApplicationScoped
public class PromptIngestionOrchestrator {

    private static final double SIMILARITY_THRESHOLD = 0.65;

    @jakarta.inject.Inject
    EmbeddingClient embeddingClient;

    @jakarta.inject.Inject
    VectorServiceClient vectorClient;

    @jakarta.inject.Inject
    SearchServiceClient searchClient;

    @jakarta.inject.Inject
    FeatureStoreClient featureStoreClient;

    @jakarta.inject.Inject
    NotificationServiceClient notificationClient;

    @jakarta.inject.Inject
    CollaborationServiceClient collaborationClient;

    @jakarta.inject.Inject
    GraphServiceClient graphClient;

    @jakarta.inject.Inject
    PromptRepository promptRepository;

    public Uni<IngestionResult> ingest(String promptId, IngestPromptRequest request) {
        return embeddingClient.embed(request.getText())
                .flatMap(embedding -> {
                    // Persist prompt first so we can return a result even if downstream services fail
                    promptRepository.save(promptId, request);
                    // Vector, search, feature store, graph: best-effort so one failing service does not fail ingest
                    Uni<Void> vectorStored = vectorClient.storePrompt(promptId, request, embedding).onFailure().recoverWithItem(() -> null);
                    Uni<Void> searchIndexed = searchClient.indexPrompt(promptId, request).onFailure().recoverWithItem(() -> null);
                    Uni<Void> featuresWritten = featureStoreClient.writePromptFeatures(promptId, request, embedding).onFailure().recoverWithItem(() -> null);
                    Uni<Void> graphRecorded = graphClient.recordPrompt(promptId, request).onFailure().recoverWithItem(() -> null);

                    return Uni.combine().all().unis(vectorStored, searchIndexed, featuresWritten, graphRecorded).discardItems()
                            .replaceWith(embedding);
                })
                .flatMap(embedding ->
                    vectorClient.searchSimilar(embedding, request.getOrgId(), request.getUserId(), 10, SIMILARITY_THRESHOLD)
                            .onFailure().recoverWithItem(() -> java.util.Collections.<VectorServiceClient.VectorMatch>emptyList())
                )
                .map(matches -> {
                    boolean similarityDetected = !matches.isEmpty();
                    List<SimilarUserInfo> similarUsers = matches.stream()
                            .map(m -> new SimilarUserInfo(m.getUserId(), m.getPromptId(), m.score(), m.getTextPreview()))
                            .toList();
                    return new IngressionResult(similarityDetected, similarUsers, promptId, request);
                })
                .flatMap(result -> {
                    if (result.similarityDetected()) {
                        // When similarity is detected, also record SIMILAR_TO relationships in the graph service
                        Uni<Void> graphSimilarities = result.similarUsers().isEmpty()
                                ? Uni.createFrom().voidItem()
                                : Uni.combine().all().unis(
                                        result.similarUsers().stream()
                                                .map(u -> graphClient.recordSimilarity(result.promptId(), u.promptId(), u.score())
                                                        .onFailure().recoverWithItem(() -> null))
                                                .toList()
                                ).discardItems();

                        return Uni.combine().all().unis(
                                        notificationClient.sendSimilarityAlert(result).onFailure().recoverWithItem(() -> null),
                                        collaborationClient.createRoomFromSimilarity(result).onFailure().recoverWithItem(() -> null),
                                        graphSimilarities.onFailure().recoverWithItem(() -> null)
                                ).discardItems()
                                .replaceWith(result);
                    }
                    return Uni.createFrom().item(result);
                })
                .map(result -> new IngestionResult(result.similarityDetected(), result.similarUsers()));
    }

    public Uni<List<SimilarPromptResult>> findSimilar(FindSimilarPromptsRequest request) {
        return embeddingClient.embed(request.getText())
                .flatMap(embedding -> vectorClient.searchSimilar(
                        embedding, request.getOrgId(), request.getUserId(),
                        request.getTopK() > 0 ? request.getTopK() : 10,
                        request.getMinScore() > 0 ? request.getMinScore() : 0.7)
                        .onFailure().recoverWithItem(() -> java.util.Collections.<VectorServiceClient.VectorMatch>emptyList()))
                .map(matches -> matches.stream()
                        .map(m -> new SimilarPromptResult(m.getPromptId(), m.getUserId(), m.score(), m.getTextPreview()))
                        .toList())
                .onFailure().recoverWithItem(() -> java.util.List.<SimilarPromptResult>of());
    }

    public Uni<List<SimilarPromptResult>> liveSimilar(String text, String orgId, String userId, int topK, double minScore) {
        if (text == null || text.isBlank()) {
            return Uni.createFrom().item(List.of());
        }
        final String safeOrg = orgId != null ? orgId : "";
        final String safeUser = userId != null ? userId : "";
        final int effectiveTopK = topK > 0 ? topK : 10;
        final double effectiveMinScore = minScore > 0 ? minScore : 0.4;

        return embeddingClient.embed(text)
                .flatMap(embedding -> vectorClient.searchSimilar(
                                embedding,
                                safeOrg,
                                safeUser,
                                effectiveTopK,
                                effectiveMinScore)
                        .onFailure().recoverWithItem(() -> java.util.Collections.<VectorServiceClient.VectorMatch>emptyList()))
                .map(matches -> matches.stream()
                        .map(m -> new SimilarPromptResult(
                                m.getPromptId(),
                                m.getUserId(),
                                m.score(),
                                m.getTextPreview() != null ? m.getTextPreview() : ""))
                        .toList())
                .onFailure().recoverWithItem(() -> List.of());
    }

    public Uni<PromptDto> getPrompt(String promptId) {
        return promptRepository.get(promptId);
    }

    public Uni<List<PromptDto>> listAllPrompts() {
        return promptRepository.listAll();
    }

    public record IngressionResult(boolean similarityDetected, List<SimilarUserInfo> similarUsers, String promptId, IngestPromptRequest request) {}
    public record IngestionResult(boolean similarityDetected, List<SimilarUserInfo> similarUsers) {}
    public record SimilarUserInfo(String userId, String promptId, double score, String textPreview) {}
    public record SimilarPromptResult(String promptId, String userId, double score, String textPreview) {}
    public record PromptDto(String promptId, String userId, String orgId, String text, long createdAt) {}
}
