package com.promptsimilarity.promptservice.rag;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory store of prompt -> LLM response for RAG. Maps promptId -> latest responseId and responseId -> StoredResponse.
 */
@ApplicationScoped
public class ResponseStore {

    private final Map<String, String> promptToResponse = new ConcurrentHashMap<>();
    private final Map<String, StoredResponse> responses = new ConcurrentHashMap<>();

    public StoredResponse save(String promptId, String userId, String orgId, String text, long tokensUsed) {
        String responseId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        StoredResponse r = new StoredResponse(responseId, promptId, userId, orgId, text, tokensUsed, now);
        responses.put(responseId, r);
        promptToResponse.put(promptId, responseId);
        return r;
    }

    public StoredResponse getByResponseId(String responseId) {
        return responses.get(responseId);
    }

    public StoredResponse getByPromptId(String promptId) {
        String responseId = promptToResponse.get(promptId);
        return responseId != null ? responses.get(responseId) : null;
    }

    public List<StoredResponse> listByOrg(String orgId) {
        return responses.values().stream()
                .filter(r -> orgId == null || orgId.equals(r.orgId()))
                .collect(Collectors.toList());
    }
}
