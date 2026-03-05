package com.promptsimilarity.gateway;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry;

import java.util.List;

/** Profile that starts WireMock so the gateway's RestClient hits the mock (proves HTTP is used, not gRPC). */
public class WireMockTestProfile implements QuarkusTestProfile {

    @Override
    public List<TestResourceEntry> testResources() {
        return List.of(new TestResourceEntry(WireMockPromptServiceResource.class));
    }
}
