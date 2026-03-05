package com.promptsimilarity.promptservice;

import com.promptsimilarity.proto.featurestore.v1.FeatureStoreServiceGrpc;
import com.promptsimilarity.proto.featurestore.v1.FeatureValue;
import com.promptsimilarity.proto.featurestore.v1.ReadPromptFeaturesRequest;
import com.promptsimilarity.proto.featurestore.v1.ReadPromptFeaturesResponse;
import com.promptsimilarity.proto.featurestore.v1.WritePromptFeaturesRequest;
import com.promptsimilarity.proto.prompt.v1.IngestPromptRequest;
import io.smallrye.mutiny.Uni;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class FeatureStoreClient {

    private static final int TTL_DAYS_SECONDS = 86400 * 7;

    @Inject
    GrpcChannels channels;

    public Uni<Void> writePromptFeatures(String promptId, IngestPromptRequest request, float[] embedding) {
        Map<String, FeatureValue> features = new HashMap<>();
        features.put("user_id", FeatureValue.newBuilder().setStringValue(request.getUserId()).build());
        features.put("org_id", FeatureValue.newBuilder().setStringValue(request.getOrgId()).build());
        if (embedding != null && embedding.length > 0) {
            ByteBuffer buf = ByteBuffer.allocate(embedding.length * 4);
            for (float v : embedding) buf.putFloat(v);
            features.put("embedding", FeatureValue.newBuilder().setBytesValue(com.google.protobuf.ByteString.copyFrom(buf.array())).build());
        }
        return Uni.createFrom().item(() -> {
            channels.featurestoreStub().writePromptFeatures(WritePromptFeaturesRequest.newBuilder()
                    .setPromptId(promptId)
                    .setUserId(request.getUserId())
                    .setOrgId(request.getOrgId())
                    .putAllFeatures(features)
                    .setTtlSeconds(TTL_DAYS_SECONDS)
                    .build());
            return null;
        }).runSubscriptionOn(channels.executor()).replaceWithVoid();
    }

    /**
     * Read the stored embedding for a prompt from the feature store, if present.
     */
    public Uni<float[]> readPromptEmbedding(String promptId) {
        return Uni.<ReadPromptFeaturesResponse>createFrom().item(() ->
                        channels.featurestoreStub().readPromptFeatures(ReadPromptFeaturesRequest.newBuilder()
                                .setPromptId(promptId)
                                .addFeatureNames("embedding")
                                .build()))
                .runSubscriptionOn(channels.executor())
                .map(resp -> {
                    FeatureValue fv = resp.getFeaturesMap().get("embedding");
                    if (fv == null || !fv.hasBytesValue()) {
                        return null;
                    }
                    byte[] bytes = fv.getBytesValue().toByteArray();
                    if (bytes.length == 0 || bytes.length % 4 != 0) {
                        return null;
                    }
                    ByteBuffer buf = ByteBuffer.wrap(bytes);
                    int len = bytes.length / 4;
                    float[] embedding = new float[len];
                    for (int i = 0; i < len; i++) {
                        embedding[i] = buf.getFloat();
                    }
                    return embedding;
                });
    }
}
