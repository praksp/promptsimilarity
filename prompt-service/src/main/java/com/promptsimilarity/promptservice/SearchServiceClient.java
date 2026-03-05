package com.promptsimilarity.promptservice;

import com.promptsimilarity.proto.prompt.v1.IngestPromptRequest;
import com.promptsimilarity.proto.search.v1.IndexPromptRequest;
import com.promptsimilarity.proto.search.v1.KeywordSearchRequest;
import com.promptsimilarity.proto.search.v1.KeywordSearchResponse;
import com.promptsimilarity.proto.search.v1.SearchHit;
import io.smallrye.mutiny.Uni;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class SearchServiceClient {

    @Inject
    GrpcChannels channels;

    public Uni<Void> indexPrompt(String promptId, IngestPromptRequest request) {
        return Uni.createFrom().item(() -> {
            channels.searchStub().indexPrompt(IndexPromptRequest.newBuilder()
                    .setPromptId(promptId)
                    .setUserId(request.getUserId())
                    .setOrgId(request.getOrgId())
                    .setText(request.getText())
                    .setCreatedAt(System.currentTimeMillis())
                    .setLanguage(request.getLanguage())
                    .build());
            return null;
        }).runSubscriptionOn(channels.executor()).replaceWithVoid();
    }

    public Uni<List<SearchHitResult>> keywordSearch(String query, String orgId, int size) {
        return Uni.<KeywordSearchResponse>createFrom().item(() ->
                        channels.searchStub().keywordSearch(KeywordSearchRequest.newBuilder()
                                .setQuery(query != null ? query : "")
                                .setOrgId(orgId != null ? orgId : "")
                                .setFrom(0)
                                .setSize(size > 0 ? size : 10)
                                .build()))
                .runSubscriptionOn(channels.executor())
                .map(resp -> resp.getHitsList().stream()
                        .map(h -> new SearchHitResult(
                                h.getPromptId(),
                                h.getUserId(),
                                h.getScore(),
                                h.getTextPreview(),
                                h.getCreatedAt()))
                        .collect(Collectors.toList()));
    }

    public record SearchHitResult(String promptId, String userId, double score, String textPreview, long createdAt) {}
}
