package com.promptsimilarity.gateway;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.List;

/**
 * REST gateway that forwards to Prompt Service via HTTP. Used by Cursor plugin and dashboard.
 */
@Path("/api/v1/prompts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PromptGatewayResource {

    @Inject
    @RestClient
    PromptServiceRestClient promptClient;

    @POST
    @Path("/ingest")
    public Uni<IngestResponseDto> ingest(IngestRequestDto dto) {
        return promptClient.ingest(dto);
    }

    @GET
    @Path("/list")
    public Uni<List<PromptDto>> listPrompts() {
        return promptClient.listPrompts()
                .onFailure().recoverWithItem(() -> List.of());
    }

    @GET
    @Path("/similar")
    public Uni<List<SimilarPromptDto>> findSimilar(
            @QueryParam("text") String text,
            @QueryParam("orgId") String orgId,
            @QueryParam("userId") String userId,
            @QueryParam("topK") @DefaultValue("10") int topK,
            @QueryParam("minScore") @DefaultValue("0.7") double minScore) {
        return promptClient.findSimilar(text, orgId, userId, topK, minScore)
                .onFailure().recoverWithItem(() -> List.of());
    }

    @GET
    @Path("/live-similar")
    public Uni<List<SimilarPromptDto>> liveSimilar(
            @QueryParam("text") String text,
            @QueryParam("orgId") String orgId,
            @QueryParam("userId") String userId,
            @QueryParam("topK") @DefaultValue("10") int topK,
            @QueryParam("minScore") @DefaultValue("0.4") double minScore) {
        return promptClient.liveSimilar(text, orgId, userId, topK, minScore)
                .onFailure().recoverWithItem(() -> List.of());
    }

    @GET
    @Path("/{promptId}")
    public Uni<PromptDto> getPrompt(@PathParam("promptId") String promptId) {
        return promptClient.getPrompt(promptId);
    }

    @POST
    @Path("/rag/ask")
    public Uni<RagAskResponseDto> ragAsk(RagAskRequestDto dto) {
        return promptClient.ragAsk(dto);
    }

    @POST
    @Path("/rag/feedback")
    public Uni<Void> ragFeedback(RagFeedbackRequestDto dto) {
        return promptClient.ragFeedback(dto);
    }

    @GET
    @Path("/rag/stats")
    public RagStatsDto ragStats(@QueryParam("orgId") String orgId) {
        return promptClient.ragStats(orgId);
    }

    public record IngestRequestDto(String userId, String orgId, String text, String language) {}
    public record IngestResponseDto(String promptId, boolean similarityDetected, List<SimilarUserDto> similarUsers) {}
    public record SimilarUserDto(String userId, String promptId, double similarityScore, String textPreview) {}
    public record SimilarPromptDto(String promptId, String userId, double score, String textPreview) {}
    public record PromptDto(String promptId, String userId, String orgId, String text, long createdAt) {}

    public record RagAskRequestDto(String prompt, String userId, String orgId) {}
    public record RagAskResponseDto(String responseText, String responseId, String promptId, long tokensUsed,
                                    double similarityScore, boolean fromCache, boolean askSatisfaction) {}
    public record RagFeedbackRequestDto(String responseId, boolean satisfied, String orgId) {}
    public record RagStatsDto(long tokensSavedTotal, long tokensSavedThisMonth, long tokensSavedOrg, long reuseCount) {}
}
