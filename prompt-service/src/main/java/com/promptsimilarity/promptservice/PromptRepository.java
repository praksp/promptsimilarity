package com.promptsimilarity.promptservice;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptsimilarity.proto.prompt.v1.IngestPromptRequest;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.ValueCommands;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Write-through cache for prompt metadata: in-memory cache with Redis persistence
 * so prompts survive restarts and users can view earlier prompts.
 */
@ApplicationScoped
public class PromptRepository {

    private static final Logger LOG = LoggerFactory.getLogger(PromptRepository.class);
    private static final String REDIS_IDS_KEY = "prompt:repo:ids";
    private static final String REDIS_KEY_PREFIX = "prompt:repo:";

    private final Map<String, PromptIngestionOrchestrator.PromptDto> store = new ConcurrentHashMap<>();
    private final ValueCommands<String, String> valueCommands;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final TypeReference<List<String>> LIST_OF_STRINGS = new TypeReference<>() {};

    public PromptRepository(RedisDataSource ds) {
        this.valueCommands = ds.value(String.class);
    }

    /** Load persisted prompts from Redis into memory on startup. */
    void onStart(@Observes StartupEvent event) {
        try {
            String idsJson = valueCommands.get(REDIS_IDS_KEY);
            if (idsJson == null || idsJson.isBlank()) {
                return;
            }
            List<String> ids = objectMapper.readValue(idsJson, LIST_OF_STRINGS);
            for (String id : ids) {
                String key = REDIS_KEY_PREFIX + id;
                String json = valueCommands.get(key);
                if (json != null && !json.isBlank()) {
                    PromptIngestionOrchestrator.PromptDto dto = fromJson(json);
                    if (dto != null) {
                        store.put(id, dto);
                    }
                }
            }
            LOG.info("Loaded {} prompts from Redis into cache", store.size());
        } catch (Exception e) {
            LOG.warn("Could not load prompts from Redis (starting with empty cache): {}", e.getMessage());
        }
    }

    /** Write-through: save to memory and persist to Redis. */
    public void save(String promptId, IngestPromptRequest request) {
        PromptIngestionOrchestrator.PromptDto dto = new PromptIngestionOrchestrator.PromptDto(
                promptId, request.getUserId(), request.getOrgId(), request.getText(), System.currentTimeMillis());
        store.put(promptId, dto);
        try {
            valueCommands.set(REDIS_KEY_PREFIX + promptId, toJson(dto));
            String idsJson = valueCommands.get(REDIS_IDS_KEY);
            List<String> ids = (idsJson != null && !idsJson.isBlank())
                    ? objectMapper.readValue(idsJson, LIST_OF_STRINGS)
                    : new java.util.ArrayList<>();
            if (!ids.contains(promptId)) {
                ids.add(promptId);
                valueCommands.set(REDIS_IDS_KEY, objectMapper.writeValueAsString(ids));
            }
        } catch (Exception e) {
            LOG.warn("Failed to persist prompt {} to Redis (in-memory copy kept): {}", promptId, e.getMessage());
        }
    }

    public Uni<PromptIngestionOrchestrator.PromptDto> get(String promptId) {
        return Uni.createFrom().item(store.get(promptId));
    }

    /** Total number of prompts (for plugin metric). */
    public long getCount() {
        return store.size();
    }

    /** All prompts, newest first (from cache). */
    public Uni<List<PromptIngestionOrchestrator.PromptDto>> listAll() {
        List<PromptIngestionOrchestrator.PromptDto> list = store.values().stream()
                .sorted(Comparator.comparingLong(PromptIngestionOrchestrator.PromptDto::createdAt).reversed())
                .toList();
        return Uni.createFrom().item(list);
    }

    private String toJson(PromptIngestionOrchestrator.PromptDto dto) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "promptId", dto.promptId(),
                    "userId", dto.userId(),
                    "orgId", dto.orgId() != null ? dto.orgId() : "",
                    "text", dto.text() != null ? dto.text() : "",
                    "createdAt", dto.createdAt()));
        } catch (Exception e) {
            throw new RuntimeException("Serialize prompt", e);
        }
    }

    private PromptIngestionOrchestrator.PromptDto fromJson(String json) {
        try {
            Map<String, Object> m = objectMapper.readValue(json, new TypeReference<>() {});
            String promptId = (String) m.get("promptId");
            String userId = (String) m.get("userId");
            String orgId = (String) m.getOrDefault("orgId", "");
            String text = (String) m.getOrDefault("text", "");
            long createdAt = ((Number) m.getOrDefault("createdAt", 0L)).longValue();
            return new PromptIngestionOrchestrator.PromptDto(promptId, userId, orgId, text, createdAt);
        } catch (Exception e) {
            LOG.warn("Failed to deserialize prompt: {}", e.getMessage());
            return null;
        }
    }
}
