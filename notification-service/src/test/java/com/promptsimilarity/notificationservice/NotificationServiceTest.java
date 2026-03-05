package com.promptsimilarity.notificationservice;

import com.promptsimilarity.proto.notification.v1.*;
import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GrpcService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class NotificationServiceTest {

    @Inject
    @GrpcService
    NotificationServiceGrpcImpl notificationService;

    @Test
    void sendSimilarityAlertSucceeds() throws Exception {
        SendSimilarityAlertRequest req = SendSimilarityAlertRequest.newBuilder()
                .setUserId("u1")
                .setOrgId("org1")
                .setPromptId("p1")
                .setSimilarityScore(0.9)
                .addTargets(SimilarityNotificationTarget.newBuilder().setUserId("u2").setPromptId("p2").setScore(0.9).build())
                .build();
        AtomicReference<SendSimilarityAlertResponse> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        notificationService.sendSimilarityAlert(req, new StreamObserver<SendSimilarityAlertResponse>() {
            @Override
            public void onNext(SendSimilarityAlertResponse r) { ref.set(r); }
            @Override
            public void onError(Throwable t) { latch.countDown(); }
            @Override
            public void onCompleted() { latch.countDown(); }
        });
        latch.await();
        assertNotNull(ref.get());
        assertTrue(ref.get().getSuccess());
    }

    @Test
    void notifyNewRoomSucceeds() throws Exception {
        NotifyNewRoomRequest req = NotifyNewRoomRequest.newBuilder()
                .setRoomId("room1")
                .setOrgId("org1")
                .setRoomType("SLACK")
                .addUserIds("u1")
                .addUserIds("u2")
                .build();
        AtomicReference<NotifyNewRoomResponse> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        notificationService.notifyNewRoom(req, new StreamObserver<NotifyNewRoomResponse>() {
            @Override
            public void onNext(NotifyNewRoomResponse r) { ref.set(r); }
            @Override
            public void onError(Throwable t) { latch.countDown(); }
            @Override
            public void onCompleted() { latch.countDown(); }
        });
        latch.await();
        assertNotNull(ref.get());
        assertTrue(ref.get().getSuccess());
    }
}
