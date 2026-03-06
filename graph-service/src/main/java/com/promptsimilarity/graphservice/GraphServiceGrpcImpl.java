package com.promptsimilarity.graphservice;

import com.promptsimilarity.proto.graph.v1.*;
import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GrpcService;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;
import io.quarkus.runtime.StartupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Base64;

@GrpcService
@Singleton
public class GraphServiceGrpcImpl extends GraphServiceGrpc.GraphServiceImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(GraphServiceGrpcImpl.class);
    private static final String REDIS_NODE_IDS = "graph:node:ids";
    private static final String REDIS_NODE_PREFIX = "graph:node:";
    private static final String REDIS_EDGE_COUNT = "graph:edge:count";
    private static final String REDIS_EDGE_PREFIX = "graph:edge:";

    private final Map<String, GraphNode> nodes = new ConcurrentHashMap<>();
    private final List<GraphEdge> edges = Collections.synchronizedList(new ArrayList<>());
    private final Object persistLock = new Object();
    private final ValueCommands<String, String> redis;

    public GraphServiceGrpcImpl(RedisDataSource ds) {
        this.redis = ds.value(String.class);
    }

    /** Load persisted graph from Redis on startup. */
    void onStart(@Observes StartupEvent event) {
        try {
            String idsStr = redis.get(REDIS_NODE_IDS);
            if (idsStr != null && !idsStr.isBlank()) {
                for (String id : idsStr.split(",")) {
                    String key = REDIS_NODE_PREFIX + id.trim();
                    if (key.equals(REDIS_NODE_PREFIX)) continue;
                    String b64 = redis.get(key);
                    if (b64 != null && !b64.isBlank()) {
                        try {
                            GraphNode n = GraphNode.parseFrom(Base64.getDecoder().decode(b64));
                            nodes.put(n.getId(), n);
                        } catch (Exception e) {
                            LOG.warn("Failed to parse graph node {}: {}", id, e.getMessage());
                        }
                    }
                }
            }
            String countStr = redis.get(REDIS_EDGE_COUNT);
            if (countStr != null && !countStr.isBlank()) {
                int count = Integer.parseInt(countStr.trim());
                for (int i = 0; i < count; i++) {
                    String b64 = redis.get(REDIS_EDGE_PREFIX + i);
                    if (b64 != null && !b64.isBlank()) {
                        try {
                            edges.add(GraphEdge.parseFrom(Base64.getDecoder().decode(b64)));
                        } catch (Exception e) {
                            LOG.warn("Failed to parse graph edge {}: {}", i, e.getMessage());
                        }
                    }
                }
            }
            LOG.info("Loaded {} nodes and {} edges from Redis", nodes.size(), edges.size());
        } catch (Exception e) {
            LOG.warn("Could not load graph from Redis (starting empty): {}", e.getMessage());
        }
    }

    private void persist() {
        synchronized (persistLock) {
            try {
                if (!nodes.isEmpty()) {
                    redis.set(REDIS_NODE_IDS, String.join(",", nodes.keySet()));
                    for (Map.Entry<String, GraphNode> e : nodes.entrySet()) {
                        redis.set(REDIS_NODE_PREFIX + e.getKey(), Base64.getEncoder().encodeToString(e.getValue().toByteArray()));
                    }
                }
                redis.set(REDIS_EDGE_COUNT, String.valueOf(edges.size()));
                for (int i = 0; i < edges.size(); i++) {
                    redis.set(REDIS_EDGE_PREFIX + i, Base64.getEncoder().encodeToString(edges.get(i).toByteArray()));
                }
            } catch (Exception e) {
                LOG.warn("Failed to persist graph to Redis: {}", e.getMessage());
            }
        }
    }

    private static String userNodeId(String userId) {
        return "user:" + userId;
    }

    private static String promptNodeId(String promptId) {
        return "prompt:" + promptId;
    }

    private static String responseNodeId(String responseId) {
        return "response:" + responseId;
    }

    private static GraphNode newResponseNode(String id, String responseId, String promptId, String userId, String orgId, long tokensUsed, long createdAt) {
        GraphNode.Builder b = GraphNode.newBuilder()
                .setId(id)
                .setType("RESPONSE")
                .putProperties("response_id", responseId)
                .putProperties("prompt_id", promptId);
        if (userId != null) b.putProperties("user_id", userId);
        if (orgId != null) b.putProperties("org_id", orgId);
        if (tokensUsed > 0) b.putProperties("tokens_used", Long.toString(tokensUsed));
        if (createdAt > 0) b.putProperties("created_at", Long.toString(createdAt));
        return b.build();
    }

    private static GraphNode newUserNode(String id, String userId, String orgId) {
        return GraphNode.newBuilder()
                .setId(id)
                .setType("USER")
                .putProperties("user_id", userId)
                .putProperties("org_id", orgId == null ? "" : orgId)
                .build();
    }

    private static GraphNode newPromptNode(String id, String promptId, String userId, String orgId, long createdAt) {
        GraphNode.Builder b = GraphNode.newBuilder()
                .setId(id)
                .setType("PROMPT")
                .putProperties("prompt_id", promptId);
        if (userId != null) {
            b.putProperties("user_id", userId);
        }
        if (orgId != null) {
            b.putProperties("org_id", orgId);
        }
        if (createdAt > 0) {
            b.putProperties("created_at", Long.toString(createdAt));
        }
        return b.build();
    }

    private static GraphEdge newEdge(String sourceId, String targetId, String type, Map<String, String> props) {
        GraphEdge.Builder b = GraphEdge.newBuilder()
                .setSourceId(sourceId)
                .setTargetId(targetId)
                .setType(type);
        if (props != null) {
            b.putAllProperties(props);
        }
        return b.build();
    }

    @Override
    public void recordPrompt(RecordPromptRequest request, StreamObserver<RecordPromptResponse> responseObserver) {
        String promptId = request.getPromptId();
        String userId = request.getUserId();
        String orgId = request.getOrgId();
        long createdAt = request.getCreatedAt();

        // Create or update user node
        String userNodeId = userNodeId(userId);
        GraphNode userNode = nodes.getOrDefault(userNodeId, newUserNode(userNodeId, userId, orgId));
        nodes.put(userNodeId, userNode);

        // Create prompt node
        String promptNodeId = promptNodeId(promptId);
        GraphNode promptNode = newPromptNode(promptNodeId, promptId, userId, orgId, createdAt);
        nodes.put(promptNodeId, promptNode);

        // Edge: USER -[AUTHORED]-> PROMPT
        GraphEdge authored = newEdge(userNodeId, promptNodeId, "AUTHORED", Collections.emptyMap());
        edges.add(authored);

        persist();
        responseObserver.onNext(RecordPromptResponse.newBuilder().setSuccess(true).build());
        responseObserver.onCompleted();
    }

    @Override
    public void recordSimilarity(RecordSimilarityRequest request, StreamObserver<RecordSimilarityResponse> responseObserver) {
        String p1 = request.getPromptId1();
        String p2 = request.getPromptId2();
        double score = request.getScore();

        String p1NodeId = promptNodeId(p1);
        String p2NodeId = promptNodeId(p2);

        // Ensure prompt nodes exist, even if RecordPrompt was not called yet
        nodes.putIfAbsent(p1NodeId, newPromptNode(p1NodeId, p1, null, null, 0));
        nodes.putIfAbsent(p2NodeId, newPromptNode(p2NodeId, p2, null, null, 0));

        Map<String, String> props = new HashMap<>();
        props.put("score", Double.toString(score));

        // Undirected similarity represented as two directed edges
        edges.add(newEdge(p1NodeId, p2NodeId, "SIMILAR_TO", props));
        edges.add(newEdge(p2NodeId, p1NodeId, "SIMILAR_TO", props));

        persist();
        responseObserver.onNext(RecordSimilarityResponse.newBuilder().setSuccess(true).build());
        responseObserver.onCompleted();
    }

    @Override
    public void recordResponse(RecordResponseRequest request, StreamObserver<RecordResponseResponse> responseObserver) {
        String promptId = request.getPromptId();
        String responseId = request.getResponseId();
        String userId = request.getUserId();
        String orgId = request.getOrgId();
        long tokensUsed = request.getTokensUsed();
        long createdAt = request.getCreatedAt();

        String promptNodeId = promptNodeId(promptId);
        String responseNodeId = responseNodeId(responseId);
        nodes.putIfAbsent(promptNodeId, newPromptNode(promptNodeId, promptId, userId, orgId, createdAt));
        GraphNode responseNode = newResponseNode(responseNodeId, responseId, promptId, userId, orgId, tokensUsed, createdAt);
        nodes.put(responseNodeId, responseNode);

        Map<String, String> props = new HashMap<>();
        if (tokensUsed > 0) props.put("tokens_used", Long.toString(tokensUsed));
        edges.add(newEdge(promptNodeId, responseNodeId, "GENERATED", props));

        persist();
        responseObserver.onNext(RecordResponseResponse.newBuilder().setSuccess(true).build());
        responseObserver.onCompleted();
    }

    @Override
    public void recordChat(RecordChatRequest request, StreamObserver<RecordChatResponse> responseObserver) {
        responseObserver.onNext(RecordChatResponse.newBuilder().setSuccess(true).build());
        responseObserver.onCompleted();
    }

    @Override
    public void getKnowledgeGraph(GetKnowledgeGraphRequest request, StreamObserver<GetKnowledgeGraphResponse> responseObserver) {
        String orgFilter = request.getOrgId();
        int maxNodes = request.hasMaxNodes() ? request.getMaxNodes() : Integer.MAX_VALUE;

        List<GraphNode> allNodes = new ArrayList<>(nodes.values());
        List<GraphEdge> allEdges = new ArrayList<>(edges);

        // Optional org filter: keep nodes whose org_id matches, and all edges between kept nodes.
        if (orgFilter != null && !orgFilter.isBlank()) {
            List<GraphNode> filteredNodes = new ArrayList<>();
            for (GraphNode n : allNodes) {
                String orgId = n.getPropertiesMap().getOrDefault("org_id", "");
                if (orgId.equals(orgFilter) || orgId.isEmpty()) {
                    filteredNodes.add(n);
                }
            }
            allNodes = filteredNodes;

            // Only keep edges where both endpoints are present
            Map<String, GraphNode> nodeIndex = new HashMap<>();
            for (GraphNode n : allNodes) {
                nodeIndex.put(n.getId(), n);
            }
            List<GraphEdge> filteredEdges = new ArrayList<>();
            for (GraphEdge e : allEdges) {
                if (nodeIndex.containsKey(e.getSourceId()) && nodeIndex.containsKey(e.getTargetId())) {
                    filteredEdges.add(e);
                }
            }
            allEdges = filteredEdges;
        }

        if (allNodes.size() > maxNodes) {
            allNodes = allNodes.subList(0, maxNodes);
        }

        responseObserver.onNext(GetKnowledgeGraphResponse.newBuilder()
                .addAllNodes(allNodes)
                .addAllEdges(allEdges)
                .build());
        responseObserver.onCompleted();
    }
}
