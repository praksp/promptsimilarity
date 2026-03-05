package com.promptsimilarity.featurestoreservice;

import com.promptsimilarity.proto.featurestore.v1.*;
import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GrpcService;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.hash.HashCommands;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.smallrye.common.annotation.Blocking;

import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@GrpcService
@Singleton
public class FeatureStoreServiceGrpcImpl extends FeatureStoreServiceGrpc.FeatureStoreServiceImplBase {

    private static final String PROMPT_FEATURES_PREFIX = "prompt:features:";
    private static final String TOPIC_AGGREGATE_PREFIX = "org:topic:";

    private final HashCommands<String, String, String> hashCommands;
    private final KeyCommands<String> keyCommands;

    public FeatureStoreServiceGrpcImpl(RedisDataSource ds) {
        this.hashCommands = ds.hash(String.class, String.class, String.class);
        this.keyCommands = ds.key();
    }

    @Override
    @Blocking
    public void writePromptFeatures(WritePromptFeaturesRequest request, StreamObserver<WritePromptFeaturesResponse> responseObserver) {
        String key = PROMPT_FEATURES_PREFIX + request.getPromptId();
        Map<String, String> fields = new HashMap<>();
        fields.put("user_id", request.getUserId());
        fields.put("org_id", request.getOrgId());
        for (Map.Entry<String, FeatureValue> e : request.getFeaturesMap().entrySet()) {
            fields.put(e.getKey(), featureValueToString(e.getValue()));
        }
        hashCommands.hset(key, fields);
        if (request.getTtlSeconds() > 0) {
            keyCommands.expire(key, Duration.ofSeconds(request.getTtlSeconds()));
        }
        responseObserver.onNext(WritePromptFeaturesResponse.newBuilder().setSuccess(true).build());
        responseObserver.onCompleted();
    }

    @Override
    @Blocking
    public void readPromptFeatures(ReadPromptFeaturesRequest request, StreamObserver<ReadPromptFeaturesResponse> responseObserver) {
        String key = PROMPT_FEATURES_PREFIX + request.getPromptId();
        Map<String, String> all = hashCommands.hgetall(key);
        ReadPromptFeaturesResponse.Builder builder = ReadPromptFeaturesResponse.newBuilder();
        if (!all.isEmpty()) {
            if (request.getFeatureNamesList().isEmpty()) {
                for (Map.Entry<String, String> e : all.entrySet()) {
                    if (!"user_id".equals(e.getKey()) && !"org_id".equals(e.getKey())) {
                        builder.putFeatures(e.getKey(), stringToFeatureValue(e.getValue()));
                    }
                }
            } else {
                for (String name : request.getFeatureNamesList()) {
                    String v = all.get(name);
                    if (v != null) builder.putFeatures(name, stringToFeatureValue(v));
                }
            }
        }
        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    @Override
    @Blocking
    public void writeTopicAggregate(WriteTopicAggregateRequest request, StreamObserver<WriteTopicAggregateResponse> responseObserver) {
        String key = TOPIC_AGGREGATE_PREFIX + request.getOrgId() + ":" + request.getTopicKey();
        Map<String, String> fields = new HashMap<>();
        fields.put("user_count", String.valueOf(request.getUserCount()));
        fields.put("updated_at", String.valueOf(request.getUpdatedAt()));
        fields.put("user_ids", String.join(",", request.getUserIdsList()));
        hashCommands.hset(key, fields);
        if (request.getTtlSeconds() > 0) {
            keyCommands.expire(key, Duration.ofSeconds(request.getTtlSeconds()));
        }
        responseObserver.onNext(WriteTopicAggregateResponse.newBuilder().setSuccess(true).build());
        responseObserver.onCompleted();
    }

    @Override
    @Blocking
    public void readTopicAggregate(ReadTopicAggregateRequest request, StreamObserver<ReadTopicAggregateResponse> responseObserver) {
        String key = TOPIC_AGGREGATE_PREFIX + request.getOrgId() + ":" + request.getTopicKey();
        Map<String, String> all = hashCommands.hgetall(key);
        ReadTopicAggregateResponse.Builder builder = ReadTopicAggregateResponse.newBuilder().setUserCount(0);
        if (!all.isEmpty()) {
            String uc = all.get("user_count");
            if (uc != null) builder.setUserCount(Integer.parseInt(uc));
            String uat = all.get("updated_at");
            if (uat != null) builder.setUpdatedAt(Long.parseLong(uat));
            String uids = all.get("user_ids");
            if (uids != null && !uids.isEmpty()) {
                for (String id : uids.split(",")) {
                    if (!id.isBlank()) builder.addUserIds(id.trim());
                }
            }
        }
        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    private static String featureValueToString(FeatureValue fv) {
        if (fv.hasBytesValue()) {
            return "b:" + Base64.getEncoder().encodeToString(fv.getBytesValue().toByteArray());
        }
        if (fv.hasDoubleValue()) return "d:" + fv.getDoubleValue();
        if (fv.hasIntValue()) return "i:" + fv.getIntValue();
        if (fv.hasStringValue()) return "s:" + fv.getStringValue();
        return "";
    }

    private static FeatureValue stringToFeatureValue(String s) {
        if (s == null || s.length() < 2) return FeatureValue.newBuilder().build();
        if (s.startsWith("b:")) {
            return FeatureValue.newBuilder().setBytesValue(com.google.protobuf.ByteString.copyFrom(Base64.getDecoder().decode(s.substring(2)))).build();
        }
        if (s.startsWith("d:")) {
            return FeatureValue.newBuilder().setDoubleValue(Double.parseDouble(s.substring(2))).build();
        }
        if (s.startsWith("i:")) {
            return FeatureValue.newBuilder().setIntValue(Long.parseLong(s.substring(2))).build();
        }
        if (s.startsWith("s:")) {
            return FeatureValue.newBuilder().setStringValue(s.substring(2)).build();
        }
        return FeatureValue.newBuilder().setStringValue(s).build();
    }
}
