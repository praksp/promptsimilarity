package com.promptsimilarity.promptservice;

import com.promptsimilarity.promptservice.rag.RagOrchestrator;
import com.promptsimilarity.proto.prompt.v1.FindSimilarPromptsRequest;
import com.promptsimilarity.proto.prompt.v1.IngestPromptRequest;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

/**
 * REST API for the gateway. Called via HTTP to avoid gRPC client/server compatibility issues.
 */
@Path("/internal/prompts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PromptRestResource {

    @jakarta.inject.Inject
    PromptIngestionOrchestrator orchestrator;

    @jakarta.inject.Inject
    RagOrchestrator ragOrchestrator;

    @POST
    @Path("/ingest")
    public Uni<IngestResponseDto> ingest(IngestRequestDto dto) {
        String promptId = UUID.randomUUID().toString();
        IngestPromptRequest request = IngestPromptRequest.newBuilder()
                .setUserId(dto.userId != null ? dto.userId : "")
                .setOrgId(dto.orgId != null ? dto.orgId : "")
                .setText(dto.text != null ? dto.text : "")
                .setLanguage(dto.language != null ? dto.language : "en")
                .build();
        return orchestrator.ingest(promptId, request)
                .map(r -> new IngestResponseDto(
                        promptId,
                        r.similarityDetected(),
                        r.similarUsers().stream()
                                .map(u -> new SimilarUserDto(u.userId(), u.promptId(), u.score(), u.textPreview()))
                                .toList()));
    }

    @GET
    @Path("/similar")
    public Uni<List<SimilarPromptDto>> findSimilar(
            @QueryParam("text") String text,
            @QueryParam("orgId") String orgId,
            @QueryParam("userId") String userId,
            @QueryParam("topK") @DefaultValue("10") int topK,
            @QueryParam("minScore") @DefaultValue("0.7") double minScore) {
        FindSimilarPromptsRequest request = FindSimilarPromptsRequest.newBuilder()
                .setText(text != null ? text : "")
                .setOrgId(orgId != null ? orgId : "")
                .setUserId(userId != null ? userId : "")
                .setTopK(topK)
                .setMinScore(minScore)
                .build();
        return orchestrator.findSimilar(request)
                .map(list -> list.stream()
                        .map(r -> new SimilarPromptDto(r.promptId(), r.userId(), r.score(), r.textPreview() != null ? r.textPreview() : ""))
                        .toList())
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
        return orchestrator.liveSimilar(
                        text != null ? text : "",
                        orgId != null ? orgId : "",
                        userId != null ? userId : "",
                        topK,
                        minScore)
                .map(list -> list.stream()
                        .map(r -> new SimilarPromptDto(r.promptId(), r.userId(), r.score(), r.textPreview() != null ? r.textPreview() : ""))
                        .toList())
                .onFailure().recoverWithItem(() -> List.of());
    }

    @GET
    @Path("/list")
    public Uni<List<PromptDto>> listPrompts() {
        return orchestrator.listAllPrompts()
                .map(list -> list.stream()
                        .map(p -> new PromptDto(p.promptId(), p.userId(), p.orgId(), p.text(), p.createdAt()))
                        .toList());
    }

    @GET
    @Path("/{promptId}")
    public Uni<PromptDto> getPrompt(@PathParam("promptId") String promptId) {
        return orchestrator.getPrompt(promptId)
                .map(p -> p != null ? new PromptDto(p.promptId(), p.userId(), p.orgId(), p.text(), p.createdAt()) : null)
                .onItem().ifNull().failWith(() -> new jakarta.ws.rs.NotFoundException());
    }

    /* ----- RAG ----- */
    @POST
    @Path("/rag/ask")
    public Uni<RagAskResponseDto> ragAsk(RagAskRequestDto dto) {
        String prompt = dto.prompt != null ? dto.prompt.trim() : "";
        String userId = dto.userId != null ? dto.userId : "";
        String orgId = dto.orgId != null ? dto.orgId : "default-org";
        return ragOrchestrator.ask(prompt, userId, orgId)
                .map(r -> new RagAskResponseDto(
                        r.responseText(),
                        r.responseId(),
                        r.promptId(),
                        r.tokensUsed(),
                        r.similarityScore(),
                        r.fromCache(),
                        r.askSatisfaction()))
                .onFailure().recoverWithItem(e -> {
                    String msg = e != null && e.getMessage() != null ? e.getMessage() : (e != null ? e.getClass().getSimpleName() : "Unknown error");
                    return new RagAskResponseDto(
                            "Request failed: " + msg,
                            null,
                            null,
                            0,
                            0,
                            false,
                            false);
                });
    }

    @POST
    @Path("/rag/feedback")
    public Uni<Void> ragFeedback(RagFeedbackRequestDto dto) {
        String responseId = dto.responseId != null ? dto.responseId : "";
        String orgId = dto.orgId != null ? dto.orgId : "default-org";
        return ragOrchestrator.feedback(responseId, dto.satisfied, orgId);
    }

    @GET
    @Path("/rag/stats")
    public RagStatsDto ragStats(@QueryParam("orgId") String orgId) {
        RagOrchestrator.RagStats s = ragOrchestrator.getStats(orgId);
        return new RagStatsDto(s.tokensSavedTotal(), s.tokensSavedThisMonth(), s.tokensSavedOrg(), s.reuseCount());
    }

    public static final class RagAskRequestDto {
        public String prompt;
        public String userId;
        public String orgId;
    }

    public static final class RagAskResponseDto {
        public String responseText;
        public String responseId;
        public String promptId;
        public long tokensUsed;
        public double similarityScore;
        public boolean fromCache;
        public boolean askSatisfaction;

        public RagAskResponseDto() {}

        public RagAskResponseDto(String responseText, String responseId, String promptId, long tokensUsed,
                                 double similarityScore, boolean fromCache, boolean askSatisfaction) {
            this.responseText = responseText;
            this.responseId = responseId;
            this.promptId = promptId;
            this.tokensUsed = tokensUsed;
            this.similarityScore = similarityScore;
            this.fromCache = fromCache;
            this.askSatisfaction = askSatisfaction;
        }
    }

    public static final class RagFeedbackRequestDto {
        public String responseId;
        public boolean satisfied;
        public String orgId;
    }

    public static final class RagStatsDto {
        public long tokensSavedTotal;
        public long tokensSavedThisMonth;
        public long tokensSavedOrg;
        public long reuseCount;

        public RagStatsDto() {}

        public RagStatsDto(long tokensSavedTotal, long tokensSavedThisMonth, long tokensSavedOrg, long reuseCount) {
            this.tokensSavedTotal = tokensSavedTotal;
            this.tokensSavedThisMonth = tokensSavedThisMonth;
            this.tokensSavedOrg = tokensSavedOrg;
            this.reuseCount = reuseCount;
        }
    }

    public static final class IngestRequestDto {
        public String userId;
        public String orgId;
        public String text;
        public String language;
    }

    public static final class IngestResponseDto {
        public String promptId;
        public boolean similarityDetected;
        public List<SimilarUserDto> similarUsers;

        public IngestResponseDto() {}

        public IngestResponseDto(String promptId, boolean similarityDetected, List<SimilarUserDto> similarUsers) {
            this.promptId = promptId;
            this.similarityDetected = similarityDetected;
            this.similarUsers = similarUsers;
        }
    }

    public static final class SimilarUserDto {
        public String userId;
        public String promptId;
        public double similarityScore;
        public String textPreview;

        public SimilarUserDto() {}

        public SimilarUserDto(String userId, String promptId, double similarityScore, String textPreview) {
            this.userId = userId;
            this.promptId = promptId;
            this.similarityScore = similarityScore;
            this.textPreview = textPreview != null ? textPreview : "";
        }
    }

    public static final class SimilarPromptDto {
        public String promptId;
        public String userId;
        public double score;
        public String textPreview;

        public SimilarPromptDto() {}

        public SimilarPromptDto(String promptId, String userId, double score, String textPreview) {
            this.promptId = promptId;
            this.userId = userId;
            this.score = score;
            this.textPreview = textPreview;
        }
    }

    public static final class PromptDto {
        public String promptId;
        public String userId;
        public String orgId;
        public String text;
        public long createdAt;

        public PromptDto() {}

        public PromptDto(String promptId, String userId, String orgId, String text, long createdAt) {
            this.promptId = promptId;
            this.userId = userId;
            this.orgId = orgId;
            this.text = text;
            this.createdAt = createdAt;
        }
    }
}
