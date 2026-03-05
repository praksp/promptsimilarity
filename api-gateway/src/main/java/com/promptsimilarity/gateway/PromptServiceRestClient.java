package com.promptsimilarity.gateway;

import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;

@RegisterRestClient(configKey = "prompt-service")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface PromptServiceRestClient {

    @POST
    @Path("/internal/prompts/ingest")
    Uni<PromptGatewayResource.IngestResponseDto> ingest(PromptGatewayResource.IngestRequestDto dto);

    @GET
    @Path("/internal/prompts/list")
    Uni<List<PromptGatewayResource.PromptDto>> listPrompts();

    @GET
    @Path("/internal/prompts/similar")
    Uni<List<PromptGatewayResource.SimilarPromptDto>> findSimilar(
            @QueryParam("text") String text,
            @QueryParam("orgId") String orgId,
            @QueryParam("userId") String userId,
            @QueryParam("topK") int topK,
            @QueryParam("minScore") double minScore);

    @GET
    @Path("/internal/prompts/live-similar")
    Uni<List<PromptGatewayResource.SimilarPromptDto>> liveSimilar(
            @QueryParam("text") String text,
            @QueryParam("orgId") String orgId,
            @QueryParam("userId") String userId,
            @QueryParam("topK") int topK,
            @QueryParam("minScore") double minScore);

    @GET
    @Path("/internal/prompts/{promptId}")
    Uni<PromptGatewayResource.PromptDto> getPrompt(@PathParam("promptId") String promptId);

    @POST
    @Path("/internal/prompts/rag/ask")
    Uni<PromptGatewayResource.RagAskResponseDto> ragAsk(PromptGatewayResource.RagAskRequestDto dto);

    @POST
    @Path("/internal/prompts/rag/feedback")
    Uni<Void> ragFeedback(PromptGatewayResource.RagFeedbackRequestDto dto);

    @GET
    @Path("/internal/prompts/rag/stats")
    PromptGatewayResource.RagStatsDto ragStats(@QueryParam("orgId") String orgId);
}
