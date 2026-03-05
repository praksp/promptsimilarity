package com.promptsimilarity.vectorservice;

import com.promptsimilarity.proto.vector.v1.*;
import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GrpcService;

import jakarta.inject.Singleton;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@GrpcService
@Singleton
public class VectorServiceGrpcImpl extends VectorServiceGrpc.VectorServiceImplBase {

    private final Map<String, VectorEntry> prompts = new ConcurrentHashMap<>();
    private final Map<String, VectorEntry> reasonings = new ConcurrentHashMap<>();

    @Override
    public void storePrompt(StorePromptRequest request, StreamObserver<StorePromptResponse> responseObserver) {
        prompts.put(request.getPromptId(), new VectorEntry(
                request.getPromptId(),
                request.getUserId(),
                request.getOrgId(),
                request.getEmbeddingList().stream().map(Float::floatValue).toList(),
                new HashMap<>(request.getMetadataMap())));
        responseObserver.onNext(StorePromptResponse.newBuilder().setSuccess(true).build());
        responseObserver.onCompleted();
    }

    @Override
    public void storeReasoning(StoreReasoningRequest request, StreamObserver<StoreReasoningResponse> responseObserver) {
        VectorEntry entry = new VectorEntry(
                request.getReasoningId(),
                request.getUserId(),
                request.getOrgId(),
                request.getEmbeddingList().stream().map(Float::floatValue).toList(),
                new HashMap<>(request.getMetadataMap()));
        reasonings.put(request.getReasoningId(), entry);
        responseObserver.onNext(StoreReasoningResponse.newBuilder().setSuccess(true).build());
        responseObserver.onCompleted();
    }

    @Override
    public void searchSimilar(SearchSimilarRequest request, StreamObserver<SearchSimilarResponse> responseObserver) {
        List<Float> query = request.getEmbeddingList().stream().toList();
        String orgId = request.getOrgId();
        String excludeUser = request.hasExcludeUserId() ? request.getExcludeUserId() : null;
        int topK = request.getTopK() > 0 ? request.getTopK() : 10;
        double minScore = request.getMinScore();

        System.out.println("SearchSimilar called. query size: " + query.size() + ", orgId: " + orgId + ", excludeUser: " + excludeUser + ", minScore: " + minScore);
        System.out.println("Total prompts in DB: " + prompts.size());

        List<VectorMatch> matches = prompts.values().stream()
                .filter(p -> p.orgId.equals(orgId) && !p.userId.equals(excludeUser))
                .map(p -> {
                    double score = cosineSimilarity(query, p.embedding);
                    System.out.println("Compared with prompt: " + p.promptId + ", user: " + p.userId + ", score: " + score);
                    return new AbstractMap.SimpleEntry<>(p, score);
                })
                .filter(e -> e.getValue() >= minScore)
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(topK)
                .map(e -> VectorMatch.newBuilder()
                        .setId(e.getKey().promptId)
                        .setScore(e.getValue())
                        .putAllMetadata(Map.of("user_id", e.getKey().userId, "text_preview", e.getKey().metadata.getOrDefault("text_preview", "")))
                        .build())
                .collect(Collectors.toList());

        System.out.println("Found " + matches.size() + " matches.");
        responseObserver.onNext(SearchSimilarResponse.newBuilder().addAllResults(matches).build());
        responseObserver.onCompleted();
    }

    private static double cosineSimilarity(List<Float> a, List<Float> b) {
        if (a.size() != b.size() || a.isEmpty()) return 0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.size(); i++) {
            float va = a.get(i), vb = b.get(i);
            dot += va * vb;
            na += va * va;
            nb += vb * vb;
        }
        double denom = Math.sqrt(na) * Math.sqrt(nb);
        return denom == 0 ? 0 : dot / denom;
    }

    private record VectorEntry(String promptId, String userId, String orgId, List<Float> embedding, Map<String, String> metadata) {}
}
