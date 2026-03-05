package com.promptsimilarity.promptservice.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * LLM provider using Ollama local API. Estimates tokens when API does not return counts.
 */
@ApplicationScoped
public class OllamaLlmClient implements LlmProvider {

    private static final int CHARS_PER_TOKEN_ESTIMATE = 4;

    @ConfigProperty(name = "llm.ollama.url", defaultValue = "http://localhost:11434")
    String ollamaUrl;

    @ConfigProperty(name = "llm.ollama.model", defaultValue = "llama2")
    String model;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Uni<LlmResult> complete(String prompt, String context) {
        if (ollamaUrl == null || ollamaUrl.isBlank()) {
            return Uni.createFrom().item(new LlmResult(
                    "[LLM not configured. Set llm.ollama.url to your Ollama endpoint.]", 0, 0));
        }
        String fullPrompt = (context != null && !context.isBlank())
                ? "Context from similar prompts:\n" + context + "\n\nUser question: " + prompt
                : prompt;
        String url = ollamaUrl.endsWith("/") ? ollamaUrl + "api/generate" : ollamaUrl + "/api/generate";
        String body;
        try {
            body = objectMapper.writeValueAsString(new GenerateRequest(model, fullPrompt, false));
        } catch (Exception e) {
            return Uni.createFrom().failure(e);
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        return Uni.createFrom().completionStage(future)
                .onItem().transform(resp -> {
                    if (resp.statusCode() == 200) {
                        return parseResult(fullPrompt, resp.body());
                    }
                    // 404 with "model ... not found" -> friendly message
                    String responseBody = resp.body() != null ? resp.body() : "";
                    if (resp.statusCode() == 404 && responseBody.contains("not found")) {
                        String hint = "Model '" + model + "' is not available. Pull it with: ollama pull " + model;
                        try {
                            JsonNode node = objectMapper.readTree(responseBody);
                            if (node.has("error")) {
                                hint = node.get("error").asText("") + ". Pull the model with: ollama pull " + model;
                            }
                        } catch (Exception ignored) { }
                        return new LlmResult(hint, estimateTokens(fullPrompt), 0);
                    }
                    throw new RuntimeException("Ollama returned " + resp.statusCode() + ": " + responseBody);
                })
                .onFailure().recoverWithItem(e -> {
                    String msg = e != null && e.getMessage() != null ? e.getMessage() : (e != null ? e.getClass().getSimpleName() : "Unknown error");
                    boolean isConnection = msg.contains("Connection") || msg.contains("refused") || msg.contains("timed out") || msg.contains("UnknownHost");
                    String text = isConnection
                            ? "LLM is currently unavailable (Ollama not reachable at " + ollamaUrl + "). Start Ollama or set llm.ollama.url to your endpoint."
                            : "LLM request failed: " + msg;
                    return new LlmResult(text, estimateTokens(fullPrompt), 0);
                });
    }

    private LlmResult parseResult(String promptUsed, String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            String text = root.has("response") ? root.get("response").asText("") : "";
            long inputTokens = root.has("prompt_eval_count") ? root.get("prompt_eval_count").asLong(0) : estimateTokens(promptUsed);
            long outputTokens = root.has("eval_count") ? root.get("eval_count").asLong(0) : estimateTokens(text);
            return new LlmResult(text, inputTokens, outputTokens);
        } catch (Exception e) {
            return new LlmResult("", estimateTokens(promptUsed), 0);
        }
    }

    private static long estimateTokens(String s) {
        if (s == null || s.isEmpty()) return 0;
        return Math.max(1, s.length() / CHARS_PER_TOKEN_ESTIMATE);
    }

    private static class GenerateRequest {
        public final String model;
        public final String prompt;
        public final boolean stream;

        GenerateRequest(String model, String prompt, boolean stream) {
            this.model = model;
            this.prompt = prompt;
            this.stream = stream;
        }
    }
}
