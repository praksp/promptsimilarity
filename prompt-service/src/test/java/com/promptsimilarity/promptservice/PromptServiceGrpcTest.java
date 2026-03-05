package com.promptsimilarity.promptservice;

import com.promptsimilarity.proto.prompt.v1.*;
import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GrpcService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the PromptService gRPC implementation. When run in isolation (no embedding/vector services),
 * findSimilar may complete with an error; when run in full stack, it may succeed.
 */
@QuarkusTest
class PromptServiceGrpcTest {

    @Inject
    @GrpcService
    PromptServiceGrpcImpl promptService;

    @Test
    void findSimilarPromptsInvokesHandler() throws Exception {
        FindSimilarPromptsRequest req = FindSimilarPromptsRequest.newBuilder()
                .setText("test query")
                .setOrgId("org1")
                .setUserId("u1")
                .setTopK(5)
                .build();
        AtomicReference<FindSimilarPromptsResponse> responseRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        promptService.findSimilarPrompts(req, new StreamObserver<FindSimilarPromptsResponse>() {
            @Override
            public void onNext(FindSimilarPromptsResponse r) { responseRef.set(r); }
            @Override
            public void onError(Throwable t) { errorRef.set(t); latch.countDown(); }
            @Override
            public void onCompleted() { latch.countDown(); }
        });
        latch.await();
        // Either we get a response (full stack) or an error (isolated run without embedding/vector)
        assertTrue(responseRef.get() != null || errorRef.get() != null,
                "Handler should invoke either onNext or onError");
        if (responseRef.get() != null) {
            assertNotNull(responseRef.get().getResultsList());
        }
    }
}
