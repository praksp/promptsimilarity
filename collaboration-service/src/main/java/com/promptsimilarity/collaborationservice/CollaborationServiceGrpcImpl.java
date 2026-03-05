package com.promptsimilarity.collaborationservice;

import com.promptsimilarity.proto.collaboration.v1.*;
import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.inject.Singleton;
import java.util.UUID;

@GrpcService
@Singleton
public class CollaborationServiceGrpcImpl extends CollaborationServiceGrpc.CollaborationServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(CollaborationServiceGrpcImpl.class);

    @Override
    public void createRoomFromSimilarity(CreateRoomFromSimilarityRequest request, StreamObserver<CreateRoomFromSimilarityResponse> responseObserver) {
        String roomId = UUID.randomUUID().toString();
        String externalRoomId = "ext-" + roomId;
        log.info("Create room: roomId={} org={} type={} users={}",
                roomId, request.getOrgId(), request.getRoomType(), request.getUserIdsList());
        responseObserver.onNext(CreateRoomFromSimilarityResponse.newBuilder()
                .setRoomId(roomId)
                .setExternalRoomId(externalRoomId)
                .setRoomType(request.getRoomType())
                .setSuccess(true)
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public void linkChatToPrompt(LinkChatToPromptRequest request, StreamObserver<LinkChatToPromptResponse> responseObserver) {
        log.info("Link chat to prompt: chatId={} roomId={} promptId={}", request.getChatId(), request.getRoomId(), request.getPromptId());
        responseObserver.onNext(LinkChatToPromptResponse.newBuilder().setSuccess(true).build());
        responseObserver.onCompleted();
    }

    @Override
    public void getRoom(GetRoomRequest request, StreamObserver<GetRoomResponse> responseObserver) {
        responseObserver.onNext(GetRoomResponse.getDefaultInstance());
        responseObserver.onCompleted();
    }
}
