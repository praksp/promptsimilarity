package com.promptsimilarity.collaborationservice;

import com.promptsimilarity.proto.collaboration.v1.*;
import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GrpcService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CollaborationServiceTest {

    @Inject
    @GrpcService
    CollaborationServiceGrpcImpl collaborationService;

    @Test
    void createRoomFromSimilaritySucceeds() throws Exception {
        CreateRoomFromSimilarityRequest req = CreateRoomFromSimilarityRequest.newBuilder()
                .setOrgId("org1")
                .setRoomType("SLACK")
                .addUserIds("u1")
                .addUserIds("u2")
                .addPromptIds("p1")
                .addPromptIds("p2")
                .build();
        AtomicReference<CreateRoomFromSimilarityResponse> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        collaborationService.createRoomFromSimilarity(req, new StreamObserver<CreateRoomFromSimilarityResponse>() {
            @Override
            public void onNext(CreateRoomFromSimilarityResponse r) { ref.set(r); }
            @Override
            public void onError(Throwable t) { latch.countDown(); }
            @Override
            public void onCompleted() { latch.countDown(); }
        });
        latch.await();
        assertNotNull(ref.get());
        assertTrue(ref.get().getSuccess());
        assertNotNull(ref.get().getRoomId());
        assertTrue(ref.get().getExternalRoomId().startsWith("ext-"));
    }

    @Test
    void linkChatToPromptSucceeds() throws Exception {
        LinkChatToPromptRequest req = LinkChatToPromptRequest.newBuilder()
                .setChatId("c1")
                .setRoomId("room1")
                .setPromptId("p1")
                .build();
        AtomicReference<LinkChatToPromptResponse> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        collaborationService.linkChatToPrompt(req, new StreamObserver<LinkChatToPromptResponse>() {
            @Override
            public void onNext(LinkChatToPromptResponse r) { ref.set(r); }
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
