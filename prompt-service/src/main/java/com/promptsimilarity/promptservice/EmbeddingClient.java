package com.promptsimilarity.promptservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Produces embeddings via the embedding service (sentence-transformers).
 * Falls back to stub embeddings if the service is not configured or fails.
 */
@ApplicationScoped
public class EmbeddingClient {

    private static final int STUB_EMBEDDING_DIM = 384;

    @ConfigProperty(name = "embedding.service.url", defaultValue = "")
    String embeddingServiceUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Uni<float[]> embed(String text) {
        if (text == null || text.isBlank()) {
            return Uni.createFrom().item(new float[STUB_EMBEDDING_DIM]);
        }
        if (embeddingServiceUrl == null || embeddingServiceUrl.isBlank()) {
            return stubEmbed(text);
        }
        String url = embeddingServiceUrl.endsWith("/") ? embeddingServiceUrl + "embed" : embeddingServiceUrl + "/embed";
        String body;
        try {
            String safeText = text != null ? text : "";
            body = objectMapper.writeValueAsString(new RequestPayload(safeText));
        } catch (Exception e) {
            return stubEmbed(text);
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8))
                .build();
        CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        return Uni.createFrom().completionStage(future)
                .onItem().transform(resp -> {
                    if (resp.statusCode() != 200) {
                        throw new RuntimeException("Embedding service returned " + resp.statusCode());
                    }
                    return parseEmbedding(resp.body());
                })
                .onFailure().recoverWithUni(() -> stubEmbed(text));
    }

    public Uni<List<Float>> embedAsList(String text) {
        return embed(text).map(arr -> {
                    java.util.List<Float> list = new java.util.ArrayList<>();
                    for (float v : arr) list.add(v);
                    return list;
                });
    }

    private float[] parseEmbedding(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode arr = root.get("embedding");
            if (arr == null || !arr.isArray()) return stubArray();
            float[] out = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                out[i] = (float) arr.get(i).asDouble();
            }
            return out;
        } catch (Exception e) {
            return stubArray();
        }
    }

    private Uni<float[]> stubEmbed(String text) {
        float[] embedding = new float[STUB_EMBEDDING_DIM];
        int hash = text.hashCode();
        for (int i = 0; i < STUB_EMBEDDING_DIM; i++) {
            embedding[i] = (float) Math.sin(hash * (i + 1) * 0.01) * 0.1f;
        }
        return Uni.createFrom().item(embedding);
    }

    private float[] stubArray() {
        return new float[STUB_EMBEDDING_DIM];
    }

    private static class RequestPayload {
        public String text;
        RequestPayload(String text) { this.text = text; }
    }
}
