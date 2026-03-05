package com.promptsimilarity.featurestoreservice;

import com.promptsimilarity.proto.featurestore.v1.*;
import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GrpcService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Requires Redis on localhost:6379 (or Quarkus Redis dev services with Docker).
 * Disabled by default so mvn test passes without Redis; enable when Redis is available.
 */
@QuarkusTest
class FeatureStoreServiceTest {

    @Inject
    @GrpcService
    FeatureStoreServiceGrpcImpl featureStoreService;

    @Test
    @Disabled("Requires Redis; run with Redis on localhost:6379 or use Docker Compose and run tests from host")
    void writeAndReadPromptFeatures() throws Exception {
        WritePromptFeaturesRequest writeReq = WritePromptFeaturesRequest.newBuilder()
                .setPromptId("p1")
                .setUserId("u1")
                .setOrgId("org1")
                .putFeatures("topic", FeatureValue.newBuilder().setStringValue("auth").build())
                .setTtlSeconds(60)
                .build();
        AtomicReference<WritePromptFeaturesResponse> writeRef = new AtomicReference<>();
        CountDownLatch writeLatch = new CountDownLatch(1);
        featureStoreService.writePromptFeatures(writeReq, new StreamObserver<WritePromptFeaturesResponse>() {
            @Override
            public void onNext(WritePromptFeaturesResponse r) { writeRef.set(r); }
            @Override
            public void onError(Throwable t) { writeLatch.countDown(); }
            @Override
            public void onCompleted() { writeLatch.countDown(); }
        });
        writeLatch.await();
        assertNotNull(writeRef.get());
        assertTrue(writeRef.get().getSuccess());

        ReadPromptFeaturesRequest readReq = ReadPromptFeaturesRequest.newBuilder().setPromptId("p1").build();
        AtomicReference<ReadPromptFeaturesResponse> readRef = new AtomicReference<>();
        CountDownLatch readLatch = new CountDownLatch(1);
        featureStoreService.readPromptFeatures(readReq, new StreamObserver<ReadPromptFeaturesResponse>() {
            @Override
            public void onNext(ReadPromptFeaturesResponse r) { readRef.set(r); }
            @Override
            public void onError(Throwable t) { readLatch.countDown(); }
            @Override
            public void onCompleted() { readLatch.countDown(); }
        });
        readLatch.await();
        assertNotNull(readRef.get());
        assertTrue(readRef.get().getFeaturesMap().containsKey("topic"));
        assertEquals("auth", readRef.get().getFeaturesMap().get("topic").getStringValue());
    }
}
