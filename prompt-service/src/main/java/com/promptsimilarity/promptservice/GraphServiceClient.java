package com.promptsimilarity.promptservice;

import com.promptsimilarity.proto.graph.v1.RecordPromptRequest;
import com.promptsimilarity.proto.graph.v1.RecordResponseRequest;
import com.promptsimilarity.proto.graph.v1.RecordSimilarityRequest;
import com.promptsimilarity.proto.prompt.v1.IngestPromptRequest;
import io.smallrye.mutiny.Uni;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class GraphServiceClient {

    @Inject
    GrpcChannels channels;

    /**
     * Record that a user authored a prompt node in the knowledge graph.
     */
    public Uni<Void> recordPrompt(String promptId, IngestPromptRequest request) {
        return Uni.createFrom().item(() -> {
            channels.graphStub().recordPrompt(RecordPromptRequest.newBuilder()
                    .setPromptId(promptId)
                    .setUserId(request.getUserId())
                    .setOrgId(request.getOrgId())
                    .setCreatedAt(System.currentTimeMillis())
                    .build());
            return null;
        }).runSubscriptionOn(channels.executor()).replaceWithVoid();
    }

    /**
     * Record a SIMILAR_TO relationship between two prompts with a similarity score.
     */
    public Uni<Void> recordSimilarity(String promptId1, String promptId2, double score) {
        return Uni.createFrom().item(() -> {
            channels.graphStub().recordSimilarity(RecordSimilarityRequest.newBuilder()
                    .setPromptId1(promptId1)
                    .setPromptId2(promptId2)
                    .setScore(score)
                    .build());
            return null;
        }).runSubscriptionOn(channels.executor()).replaceWithVoid();
    }

    /**
     * Record a GENERATED edge: Prompt -[GENERATED]-> Response (LLM response with token count).
     */
    public Uni<Void> recordResponse(String promptId, String responseId, String userId, String orgId, long tokensUsed, long createdAt) {
        return Uni.createFrom().item(() -> {
            channels.graphStub().recordResponse(RecordResponseRequest.newBuilder()
                    .setPromptId(promptId)
                    .setResponseId(responseId)
                    .setUserId(userId != null ? userId : "")
                    .setOrgId(orgId != null ? orgId : "")
                    .setTokensUsed(tokensUsed)
                    .setCreatedAt(createdAt > 0 ? createdAt : System.currentTimeMillis())
                    .build());
            return null;
        }).runSubscriptionOn(channels.executor()).replaceWithVoid();
    }
}
