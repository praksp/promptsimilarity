package com.promptsimilarity.gateway;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.*;

@QuarkusTest
class PromptGatewayResourceTest {

    @Test
    void healthEndpointReturnsOk() {
        given()
                .when().get("/q/health")
                .then()
                .statusCode(200)
                .body(containsString("UP"));
    }

    @Test
    void ingestWhenPromptServiceUnavailableReturnsErrorWithMessage() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"userId\":\"u1\",\"orgId\":\"org1\",\"text\":\"test prompt\",\"language\":\"en\"}")
                .when().post("/api/v1/prompts/ingest")
                .then()
                .statusCode(500)
                .body("message", notNullValue());
    }

    @Test
    void findSimilarWhenPromptServiceUnavailableReturnsEmptyList() {
        given()
                .queryParam("text", "hello")
                .queryParam("orgId", "org1")
                .queryParam("userId", "u1")
                .when().get("/api/v1/prompts/similar")
                .then()
                .statusCode(200)
                .body("size()", equalTo(0));
    }

    @Test
    void listPromptsWhenPromptServiceUnavailableReturnsEmptyList() {
        given()
                .when().get("/api/v1/prompts/list")
                .then()
                .statusCode(200)
                .body("size()", equalTo(0));
    }
}

/**
 * Validates the gateway uses REST client (HTTP) to call prompt-service, not gRPC.
 * If the gateway used gRPC, this test would fail (e.g. connection to wrong port or gRPC stub).
 */
@QuarkusTest
@TestProfile(WireMockTestProfile.class)
class PromptGatewayRestClientTest {

    @Test
    void ingestCallsPromptServiceViaRestAndReturnsMockedResponse() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"userId\":\"u1\",\"orgId\":\"org1\",\"text\":\"test prompt\",\"language\":\"en\"}")
                .when().post("/api/v1/prompts/ingest")
                .then()
                .statusCode(200)
                .body("promptId", equalTo("test-id-123"))
                .body("similarityDetected", equalTo(false))
                .body("similarUsers", notNullValue());
    }

    @Test
    void listPromptsReturnsArrayFromPromptService() {
        given()
                .when().get("/api/v1/prompts/list")
                .then()
                .statusCode(200)
                .body("size()", equalTo(0));
    }
}
