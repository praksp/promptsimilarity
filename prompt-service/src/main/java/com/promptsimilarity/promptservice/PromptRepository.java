package com.promptsimilarity.promptservice;

import com.promptsimilarity.proto.prompt.v1.IngestPromptRequest;
import io.smallrye.mutiny.Uni;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for prompt metadata. Replace with PostgreSQL/Cassandra in production.
 */
@ApplicationScoped
public class PromptRepository {

    private final Map<String, PromptIngestionOrchestrator.PromptDto> store = new ConcurrentHashMap<>();

    public void save(String promptId, IngestPromptRequest request) {
        store.put(promptId, new PromptIngestionOrchestrator.PromptDto(
                promptId, request.getUserId(), request.getOrgId(), request.getText(), System.currentTimeMillis()));
    }

    public Uni<PromptIngestionOrchestrator.PromptDto> get(String promptId) {
        return Uni.createFrom().item(store.get(promptId));
    }

    /** All prompts, newest first. */
    public Uni<List<PromptIngestionOrchestrator.PromptDto>> listAll() {
        List<PromptIngestionOrchestrator.PromptDto> list = store.values().stream()
                .sorted(Comparator.comparingLong(PromptIngestionOrchestrator.PromptDto::createdAt).reversed())
                .toList();
        return Uni.createFrom().item(list);
    }
}
