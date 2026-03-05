package com.promptsimilarity.promptservice;

import com.promptsimilarity.proto.notification.v1.*;
import io.smallrye.mutiny.Uni;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class NotificationServiceClient {

    @Inject
    GrpcChannels channels;

    public Uni<Void> sendSimilarityAlert(PromptIngestionOrchestrator.IngressionResult result) {
        var builder = SendSimilarityAlertRequest.newBuilder()
                .setUserId(result.request().getUserId())
                .setOrgId(result.request().getOrgId())
                .setPromptId(result.promptId())
                .setSimilarityScore(result.similarUsers().isEmpty() ? 0 : result.similarUsers().get(0).score());
        for (PromptIngestionOrchestrator.SimilarUserInfo u : result.similarUsers()) {
            builder.addTargets(SimilarityNotificationTarget.newBuilder()
                    .setUserId(u.userId())
                    .setPromptId(u.promptId())
                    .setScore(u.score())
                    .build());
        }
        return Uni.createFrom().item(() -> {
            channels.notificationStub().sendSimilarityAlert(builder.build());
            return null;
        }).runSubscriptionOn(channels.executor()).replaceWithVoid();
    }
}
