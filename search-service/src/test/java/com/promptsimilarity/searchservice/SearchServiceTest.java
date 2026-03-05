package com.promptsimilarity.searchservice;

import com.promptsimilarity.proto.search.v1.*;
import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GrpcService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class SearchServiceTest {

    @Inject
    @GrpcService
    SearchServiceGrpcImpl searchService;

    @Test
    void indexAndKeywordSearch() throws Exception {
        IndexPromptRequest indexReq = IndexPromptRequest.newBuilder()
                .setPromptId("p1")
                .setUserId("u1")
                .setOrgId("org1")
                .setText("How to implement authentication in Java")
                .setCreatedAt(System.currentTimeMillis())
                .setLanguage("en")
                .build();
        AtomicReference<IndexPromptResponse> indexResRef = new AtomicReference<>();
        CountDownLatch indexLatch = new CountDownLatch(1);
        searchService.indexPrompt(indexReq, new StreamObserver<IndexPromptResponse>() {
            @Override
            public void onNext(IndexPromptResponse r) { indexResRef.set(r); }
            @Override
            public void onError(Throwable t) { indexLatch.countDown(); }
            @Override
            public void onCompleted() { indexLatch.countDown(); }
        });
        indexLatch.await();
        assertNotNull(indexResRef.get());
        assertTrue(indexResRef.get().getSuccess());

        KeywordSearchRequest searchReq = KeywordSearchRequest.newBuilder()
                .setQuery("authentication")
                .setOrgId("org1")
                .setSize(10)
                .build();
        AtomicReference<KeywordSearchResponse> searchResRef = new AtomicReference<>();
        CountDownLatch searchLatch = new CountDownLatch(1);
        searchService.keywordSearch(searchReq, new StreamObserver<KeywordSearchResponse>() {
            @Override
            public void onNext(KeywordSearchResponse r) { searchResRef.set(r); }
            @Override
            public void onError(Throwable t) { searchLatch.countDown(); }
            @Override
            public void onCompleted() { searchLatch.countDown(); }
        });
        searchLatch.await();
        assertNotNull(searchResRef.get());
        assertFalse(searchResRef.get().getHitsList().isEmpty());
        assertTrue(searchResRef.get().getHits(0).getTextPreview().toLowerCase().contains("auth"));
    }
}
