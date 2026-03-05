package com.promptsimilarity.graphservice;

import com.promptsimilarity.proto.graph.v1.*;
import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GrpcService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class GraphServiceTest {

    @Inject
    @GrpcService
    GraphServiceGrpcImpl graphService;

    @Test
    void recordPromptSucceeds() throws Exception {
        RecordPromptRequest req = RecordPromptRequest.newBuilder()
                .setPromptId("p1")
                .setUserId("u1")
                .setOrgId("org1")
                .setCreatedAt(System.currentTimeMillis())
                .build();
        AtomicReference<RecordPromptResponse> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        graphService.recordPrompt(req, new StreamObserver<RecordPromptResponse>() {
            @Override
            public void onNext(RecordPromptResponse r) { ref.set(r); }
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
    void getKnowledgeGraphReturnsEmpty() throws Exception {
        GetKnowledgeGraphRequest req = GetKnowledgeGraphRequest.newBuilder().setOrgId("org1").build();
        AtomicReference<GetKnowledgeGraphResponse> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        graphService.getKnowledgeGraph(req, new StreamObserver<GetKnowledgeGraphResponse>() {
            @Override
            public void onNext(GetKnowledgeGraphResponse r) { ref.set(r); }
            @Override
            public void onError(Throwable t) { latch.countDown(); }
            @Override
            public void onCompleted() { latch.countDown(); }
        });
        latch.await();
        assertNotNull(ref.get());
        assertTrue(ref.get().getNodesList().isEmpty());
        assertTrue(ref.get().getEdgesList().isEmpty());
    }
}
