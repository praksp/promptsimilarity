package com.promptsimilarity.promptservice;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class EmbeddingClientTest {

    @Inject
    EmbeddingClient embeddingClient;

    @Test
    void embedReturnsNonEmptyArray() {
        float[] result = embeddingClient.embed("Hello world").await().indefinitely();
        assertNotNull(result);
        assertTrue(result.length > 0);
    }

    @Test
    void embedEmptyStringReturnsZeroVector() {
        float[] result = embeddingClient.embed("").await().indefinitely();
        assertNotNull(result);
        assertEquals(384, result.length);
    }

    @Test
    void embedIsDeterministic() {
        String text = "Same text";
        float[] a = embeddingClient.embed(text).await().indefinitely();
        float[] b = embeddingClient.embed(text).await().indefinitely();
        assertArrayEquals(a, b);
    }
}
