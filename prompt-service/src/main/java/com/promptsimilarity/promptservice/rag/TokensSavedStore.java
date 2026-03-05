package com.promptsimilarity.promptservice.rag;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.YearMonth;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counters for tokens saved by reusing RAG responses (total, per org, per month).
 */
@ApplicationScoped
public class TokensSavedStore {

    private final LongAdder total = new LongAdder();
    private final LongAdder reuseCount = new LongAdder();
    private final Map<String, LongAdder> byOrg = new ConcurrentHashMap<>();
    private final Map<YearMonth, LongAdder> byMonth = new ConcurrentHashMap<>();

    public void addSaved(long tokens, String orgId) {
        total.add(tokens);
        reuseCount.add(1);
        if (orgId != null && !orgId.isBlank()) {
            byOrg.computeIfAbsent(orgId, k -> new LongAdder()).add(tokens);
        }
        byMonth.computeIfAbsent(YearMonth.now(), k -> new LongAdder()).add(tokens);
    }

    public long getTotalSaved() {
        return total.sum();
    }

    public long getSavedForOrg(String orgId) {
        if (orgId == null || orgId.isBlank()) return 0;
        LongAdder a = byOrg.get(orgId);
        return a != null ? a.sum() : 0;
    }

    public long getSavedThisMonth() {
        return byMonth.getOrDefault(YearMonth.now(), new LongAdder()).sum();
    }

    public long getReuseCount() {
        return reuseCount.sum();
    }
}
