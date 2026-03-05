package com.promptsimilarity.searchservice;

import com.promptsimilarity.proto.search.v1.*;
import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GrpcService;

import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@GrpcService
@Singleton
public class SearchServiceGrpcImpl extends SearchServiceGrpc.SearchServiceImplBase {

    private final Map<String, IndexedPrompt> index = new ConcurrentHashMap<>();

    @Override
    public void indexPrompt(IndexPromptRequest request, StreamObserver<IndexPromptResponse> responseObserver) {
        String preview = request.getText().length() > 200 ? request.getText().substring(0, 200) + "..." : request.getText();
        index.put(request.getPromptId(), new IndexedPrompt(
                request.getPromptId(), request.getUserId(), request.getOrgId(),
                request.getText(), request.getCreatedAt(), request.getLanguage(), preview));
        responseObserver.onNext(IndexPromptResponse.newBuilder().setSuccess(true).build());
        responseObserver.onCompleted();
    }

    @Override
    public void keywordSearch(KeywordSearchRequest request, StreamObserver<KeywordSearchResponse> responseObserver) {
        String q = request.getQuery().toLowerCase();
        List<SearchHit> hits = index.values().stream()
                .filter(p -> p.text.toLowerCase().contains(q) && p.orgId.equals(request.getOrgId()))
                .sorted((a, b) -> Long.compare(b.createdAt, a.createdAt))
                .skip(request.getFrom())
                .limit(request.getSize() > 0 ? request.getSize() : 10)
                .map(p -> SearchHit.newBuilder()
                        .setPromptId(p.promptId)
                        .setUserId(p.userId)
                        .setScore(1.0)
                        .setTextPreview(p.preview)
                        .setCreatedAt(p.createdAt)
                        .build())
                .collect(Collectors.toList());
        responseObserver.onNext(KeywordSearchResponse.newBuilder().addAllHits(hits).setTotal(hits.size()).build());
        responseObserver.onCompleted();
    }

    @Override
    public void hybridSearch(HybridSearchRequest request, StreamObserver<HybridSearchResponse> responseObserver) {
        keywordSearch(KeywordSearchRequest.newBuilder()
                        .setQuery(request.getQuery())
                        .setOrgId(request.getOrgId())
                        .setSize(request.getSize())
                        .build(),
                new StreamObserver<KeywordSearchResponse>() {
                    @Override
                    public void onNext(KeywordSearchResponse k) {
                        responseObserver.onNext(HybridSearchResponse.newBuilder().addAllHits(k.getHitsList()).setTotal(k.getTotal()).build());
                    }
                    @Override
                    public void onError(Throwable t) { responseObserver.onError(t); }
                    @Override
                    public void onCompleted() { responseObserver.onCompleted(); }
                });
    }

    private record IndexedPrompt(String promptId, String userId, String orgId, String text, long createdAt, String language, String preview) {}
}
