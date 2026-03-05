package com.promptsimilarity.promptservice;

import com.promptsimilarity.proto.prompt.v1.*;
import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GrpcService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@GrpcService
@Singleton
public class PromptServiceGrpcImpl extends PromptServiceGrpc.PromptServiceImplBase {

    @Inject
    PromptIngestionOrchestrator orchestrator;

    @Override
    public void ingestPrompt(IngestPromptRequest request, StreamObserver<IngestPromptResponse> responseObserver) {
        String promptId = UUID.randomUUID().toString();
        orchestrator.ingest(promptId, request)
                .subscribe().with(
                        result -> {
                            responseObserver.onNext(IngestPromptResponse.newBuilder()
                                    .setPromptId(promptId)
                                    .setSimilarityDetected(result.similarityDetected())
                                    .addAllSimilarUsers(result.similarUsers().stream()
                                            .map(u -> SimilarUser.newBuilder()
                                                    .setUserId(u.userId())
                                                    .setPromptId(u.promptId())
                                                    .setSimilarityScore(u.score())
                                                    .build())
                                            .collect(Collectors.toList()))
                                    .build());
                            responseObserver.onCompleted();
                        },
                        responseObserver::onError);
    }

    @Override
    public void findSimilarPrompts(FindSimilarPromptsRequest request, StreamObserver<FindSimilarPromptsResponse> responseObserver) {
        orchestrator.findSimilar(request)
                .subscribe().with(
                        results -> {
                            responseObserver.onNext(FindSimilarPromptsResponse.newBuilder()
                                    .addAllResults(results.stream()
                                            .map(r -> com.promptsimilarity.proto.prompt.v1.SimilarPromptResult.newBuilder()
                                                    .setPromptId(r.promptId())
                                                    .setUserId(r.userId())
                                                    .setScore(r.score())
                                                    .setTextPreview(r.textPreview())
                                                    .build())
                                            .collect(Collectors.toList()))
                                    .build());
                            responseObserver.onCompleted();
                        },
                        responseObserver::onError);
    }

    @Override
    public void getPrompt(GetPromptRequest request, StreamObserver<GetPromptResponse> responseObserver) {
        orchestrator.getPrompt(request.getPromptId())
                .subscribe().with(
                        p -> {
                            if (p == null) {
                                responseObserver.onNext(GetPromptResponse.getDefaultInstance());
                            } else {
                                responseObserver.onNext(GetPromptResponse.newBuilder()
                                        .setPromptId(p.promptId())
                                        .setUserId(p.userId())
                                        .setOrgId(p.orgId())
                                        .setText(p.text())
                                        .setCreatedAt(p.createdAt())
                                        .build());
                            }
                            responseObserver.onCompleted();
                        },
                        responseObserver::onError);
    }
}
