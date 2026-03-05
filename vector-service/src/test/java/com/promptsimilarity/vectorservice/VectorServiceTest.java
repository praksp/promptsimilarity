package com.promptsimilarity.vectorservice;

import com.promptsimilarity.proto.vector.v1.*;
import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GrpcService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class VectorServiceTest {

    @Inject
    @GrpcService
    VectorServiceGrpcImpl vectorService;

    @Test
    void storeAndSearchPrompt() throws Exception {
        StorePromptRequest storeReq = StorePromptRequest.newBuilder()
                .setPromptId("p1")
                .setUserId("u1")
                .setOrgId("org1")
                .addAllEmbedding(List.of(0.1f, 0.2f, 0.3f))
                .build();
        AtomicReference<StorePromptResponse> storeResRef = new AtomicReference<>();
        CountDownLatch storeLatch = new CountDownLatch(1);
        vectorService.storePrompt(storeReq, new StreamObserver<StorePromptResponse>() {
            @Override
            public void onNext(StorePromptResponse r) { storeResRef.set(r); }
            @Override
            public void onError(Throwable t) { storeLatch.countDown(); }
            @Override
            public void onCompleted() { storeLatch.countDown(); }
        });
        storeLatch.await();
        assertNotNull(storeResRef.get());
        assertTrue(storeResRef.get().getSuccess());

        SearchSimilarRequest searchReq = SearchSimilarRequest.newBuilder()
                .addAllEmbedding(List.of(0.1f, 0.2f, 0.3f))
                .setOrgId("org1")
                .setTopK(5)
                .setMinScore(0.0)
                .build();
        AtomicReference<SearchSimilarResponse> searchResRef = new AtomicReference<>();
        CountDownLatch searchLatch = new CountDownLatch(1);
        vectorService.searchSimilar(searchReq, new StreamObserver<SearchSimilarResponse>() {
            @Override
            public void onNext(SearchSimilarResponse r) { searchResRef.set(r); }
            @Override
            public void onError(Throwable t) { searchLatch.countDown(); }
            @Override
            public void onCompleted() { searchLatch.countDown(); }
        });
        searchLatch.await();
        assertNotNull(searchResRef.get());
        assertFalse(searchResRef.get().getResultsList().isEmpty());
        assertEquals("p1", searchResRef.get().getResults(0).getId());
    }
}
