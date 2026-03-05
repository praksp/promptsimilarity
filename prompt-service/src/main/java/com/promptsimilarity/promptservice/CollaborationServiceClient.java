package com.promptsimilarity.promptservice;

import com.promptsimilarity.proto.collaboration.v1.CreateRoomFromSimilarityRequest;
import io.smallrye.mutiny.Uni;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class CollaborationServiceClient {

    @Inject
    GrpcChannels channels;

    public Uni<Void> createRoomFromSimilarity(PromptIngestionOrchestrator.IngressionResult result) {
        List<String> userIds = result.similarUsers().stream()
                .map(PromptIngestionOrchestrator.SimilarUserInfo::userId)
                .collect(Collectors.toList());
        if (!userIds.contains(result.request().getUserId())) {
            userIds = new ArrayList<>(userIds);
            userIds.add(result.request().getUserId());
        }
        List<String> promptIds = result.similarUsers().stream()
                .map(PromptIngestionOrchestrator.SimilarUserInfo::promptId)
                .collect(Collectors.toList());
        promptIds = new ArrayList<>(promptIds);
        promptIds.add(result.promptId());

        var req = CreateRoomFromSimilarityRequest.newBuilder()
                .setOrgId(result.request().getOrgId())
                .addAllUserIds(userIds)
                .addAllPromptIds(promptIds)
                .setRoomType("SLACK")
                .build();
        return Uni.createFrom().item(() -> {
            channels.collaborationStub().createRoomFromSimilarity(req);
            return null;
        }).runSubscriptionOn(channels.executor()).replaceWithVoid();
    }
}
