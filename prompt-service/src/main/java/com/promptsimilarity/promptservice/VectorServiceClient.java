package com.promptsimilarity.promptservice;

import com.promptsimilarity.proto.prompt.v1.IngestPromptRequest;
import com.promptsimilarity.proto.vector.v1.StorePromptRequest;
import com.promptsimilarity.proto.vector.v1.StoreReasoningRequest;
import com.promptsimilarity.proto.vector.v1.StoreReasoningResponse;
import com.promptsimilarity.proto.vector.v1.*;
import io.smallrye.mutiny.Uni;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class VectorServiceClient {

    private static final Logger log = LoggerFactory.getLogger(VectorServiceClient.class);

    @Inject
    GrpcChannels channels;

    private static List<Float> toList(float[] a) {
        List<Float> list = new ArrayList<>();
        for (float v : a) list.add(v);
        return list;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    public Uni<Void> storePrompt(String promptId, IngestPromptRequest request, float[] embedding) {
        return Uni.createFrom().item(() -> {
            log.info("Storing prompt in vector db: {}", promptId);
            channels.vectorStub().storePrompt(StorePromptRequest.newBuilder()
                    .setPromptId(promptId)
                    .setUserId(request.getUserId())
                    .setOrgId(request.getOrgId())
                    .addAllEmbedding(toList(embedding))
                    .putMetadata("language", request.getLanguage())
                    .putMetadata("text_preview", truncate(request.getText(), 500))
                    .build());
            log.info("Stored prompt successfully: {}", promptId);
            return null;
        }).runSubscriptionOn(channels.executor()).replaceWithVoid()
          .onFailure().invoke(e -> log.error("Failed to store prompt in vector db: " + promptId, e));
    }

    /**
     * Store LLM response embedding (reasoning) for future similarity search.
     */
    public Uni<Void> storeReasoning(String responseId, String promptId, String userId, String orgId, float[] embedding, String textPreview) {
        return Uni.<StoreReasoningResponse>createFrom().item(() -> {
            log.info("Storing response/reasoning in vector db: {}", responseId);
            return channels.vectorStub().storeReasoning(StoreReasoningRequest.newBuilder()
                    .setReasoningId(responseId)
                    .setPromptId(promptId)
                    .setUserId(userId != null ? userId : "")
                    .setOrgId(orgId != null ? orgId : "")
                    .addAllEmbedding(toList(embedding))
                    .putMetadata("text_preview", truncate(textPreview, 500))
                    .build());
        }).runSubscriptionOn(channels.executor()).replaceWithVoid()
          .onFailure().invoke(e -> log.error("Failed to store reasoning in vector db: " + responseId, e));
    }

    public Uni<List<VectorMatch>> searchSimilar(float[] embedding, String orgId, String excludeUserId, int topK, double minScore) {
        return Uni.<SearchSimilarResponse>createFrom().item(() -> {
                    log.info("Searching similar in vector db...");
                    return channels.vectorStub().searchSimilar(SearchSimilarRequest.newBuilder()
                            .addAllEmbedding(toList(embedding))
                            .setOrgId(orgId)
                            .setExcludeUserId(excludeUserId != null ? excludeUserId : "")
                            .setTopK(topK)
                            .setMinScore(minScore)
                            .build());
                })
                .runSubscriptionOn(channels.executor())
                .map(res -> res.getResultsList().stream()
                        .map(r -> new VectorMatch(r.getId(), r.getScore(), r.getMetadataMap()))
                        .collect(Collectors.toList()))
                .onItem().invoke(res -> log.info("Found {} similar prompts", res.size()))
                .onFailure().invoke(e -> log.error("Failed to search similar in vector db", e))
                .onFailure().recoverWithItem(() -> Collections.emptyList());
    }

    public record VectorMatch(String id, double score, java.util.Map<String, String> metadata) {
        public String getUserId() { return metadata.getOrDefault("user_id", ""); }
        public String getPromptId() { return id; }
        public String getTextPreview() { return metadata.getOrDefault("text_preview", ""); }
    }
}
