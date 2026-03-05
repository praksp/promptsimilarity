package com.promptsimilarity.notificationservice;

import com.promptsimilarity.proto.notification.v1.*;
import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.inject.Singleton;

@GrpcService
@Singleton
public class NotificationServiceGrpcImpl extends NotificationServiceGrpc.NotificationServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(NotificationServiceGrpcImpl.class);

    @Override
    public void sendSimilarityAlert(SendSimilarityAlertRequest request, StreamObserver<SendSimilarityAlertResponse> responseObserver) {
        log.info("Similarity alert: user={} org={} promptId={} targets={}",
                request.getUserId(), request.getOrgId(), request.getPromptId(), request.getTargetsCount());
        responseObserver.onNext(SendSimilarityAlertResponse.newBuilder().setSuccess(true).build());
        responseObserver.onCompleted();
    }

    @Override
    public void notifyNewRoom(NotifyNewRoomRequest request, StreamObserver<NotifyNewRoomResponse> responseObserver) {
        log.info("New room: roomId={} org={} type={} users={}",
                request.getRoomId(), request.getOrgId(), request.getRoomType(), request.getUserIdsList());
        responseObserver.onNext(NotifyNewRoomResponse.newBuilder().setSuccess(true).build());
        responseObserver.onCompleted();
    }
}
