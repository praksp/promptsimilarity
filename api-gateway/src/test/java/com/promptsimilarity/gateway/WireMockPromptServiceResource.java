package com.promptsimilarity.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

/**
 * Starts WireMock for tests so the gateway's RestClient hits the mock instead of a real prompt-service.
 * Proves the gateway uses HTTP REST (not gRPC).
 */
public class WireMockPromptServiceResource implements QuarkusTestResourceLifecycleManager {

    private WireMockServer server;

    @Override
    public Map<String, String> start() {
        server = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        server.start();

        server.stubFor(post(urlPathEqualTo("/internal/prompts/ingest"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"promptId\":\"test-id-123\",\"similarityDetected\":false,\"similarUsers\":[]}")));

        server.stubFor(get(urlPathEqualTo("/internal/prompts/list"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[]")));

        server.stubFor(get(urlPathMatching("/internal/prompts/similar.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[]")));

        return Map.of("quarkus.rest-client.prompt-service.url", server.baseUrl());
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop();
        }
    }
}
