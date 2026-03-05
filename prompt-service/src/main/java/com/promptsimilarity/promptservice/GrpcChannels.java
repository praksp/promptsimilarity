package com.promptsimilarity.promptservice;

import com.promptsimilarity.proto.vector.v1.VectorServiceGrpc;
import com.promptsimilarity.proto.search.v1.SearchServiceGrpc;
import com.promptsimilarity.proto.notification.v1.NotificationServiceGrpc;
import com.promptsimilarity.proto.collaboration.v1.CollaborationServiceGrpc;
import com.promptsimilarity.proto.graph.v1.GraphServiceGrpc;
import com.promptsimilarity.proto.featurestore.v1.FeatureStoreServiceGrpc;
import io.grpc.Deadline;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class GrpcChannels {

    @ConfigProperty(name = "quarkus.grpc.clients.vector.host", defaultValue = "localhost")
    String vectorHost;
    @ConfigProperty(name = "quarkus.grpc.clients.vector.port", defaultValue = "9002")
    int vectorPort;
    @ConfigProperty(name = "quarkus.grpc.clients.search.host", defaultValue = "localhost")
    String searchHost;
    @ConfigProperty(name = "quarkus.grpc.clients.search.port", defaultValue = "9003")
    int searchPort;
    @ConfigProperty(name = "quarkus.grpc.clients.notification.host", defaultValue = "localhost")
    String notificationHost;
    @ConfigProperty(name = "quarkus.grpc.clients.notification.port", defaultValue = "9004")
    int notificationPort;
    @ConfigProperty(name = "quarkus.grpc.clients.collaboration.host", defaultValue = "localhost")
    String collaborationHost;
    @ConfigProperty(name = "quarkus.grpc.clients.collaboration.port", defaultValue = "9005")
    int collaborationPort;
    @ConfigProperty(name = "quarkus.grpc.clients.graph.host", defaultValue = "localhost")
    String graphHost;
    @ConfigProperty(name = "quarkus.grpc.clients.graph.port", defaultValue = "9006")
    int graphPort;
    @ConfigProperty(name = "quarkus.grpc.clients.featurestore.host", defaultValue = "localhost")
    String featurestoreHost;
    @ConfigProperty(name = "quarkus.grpc.clients.featurestore.port", defaultValue = "9007")
    int featurestorePort;

    private final ExecutorService executor = Executors.newFixedThreadPool(8);
    private volatile ManagedChannel vectorCh, searchCh, notificationCh, collaborationCh, graphCh, featurestoreCh;

    public ExecutorService executor() { return executor; }

    private static final int GRPC_DEADLINE_SECONDS = 90;

    public VectorServiceGrpc.VectorServiceBlockingStub vectorStub() {
        if (vectorCh == null) vectorCh = ManagedChannelBuilder.forAddress(vectorHost, vectorPort).usePlaintext().build();
        return VectorServiceGrpc.newBlockingStub(vectorCh).withDeadline(Deadline.after(GRPC_DEADLINE_SECONDS, TimeUnit.SECONDS));
    }
    public SearchServiceGrpc.SearchServiceBlockingStub searchStub() {
        if (searchCh == null) searchCh = ManagedChannelBuilder.forAddress(searchHost, searchPort).usePlaintext().build();
        return SearchServiceGrpc.newBlockingStub(searchCh).withDeadline(Deadline.after(GRPC_DEADLINE_SECONDS, TimeUnit.SECONDS));
    }
    public NotificationServiceGrpc.NotificationServiceBlockingStub notificationStub() {
        if (notificationCh == null) notificationCh = ManagedChannelBuilder.forAddress(notificationHost, notificationPort).usePlaintext().build();
        return NotificationServiceGrpc.newBlockingStub(notificationCh).withDeadline(Deadline.after(GRPC_DEADLINE_SECONDS, TimeUnit.SECONDS));
    }
    public CollaborationServiceGrpc.CollaborationServiceBlockingStub collaborationStub() {
        if (collaborationCh == null) collaborationCh = ManagedChannelBuilder.forAddress(collaborationHost, collaborationPort).usePlaintext().build();
        return CollaborationServiceGrpc.newBlockingStub(collaborationCh).withDeadline(Deadline.after(GRPC_DEADLINE_SECONDS, TimeUnit.SECONDS));
    }
    public GraphServiceGrpc.GraphServiceBlockingStub graphStub() {
        if (graphCh == null) graphCh = ManagedChannelBuilder.forAddress(graphHost, graphPort).usePlaintext().build();
        return GraphServiceGrpc.newBlockingStub(graphCh).withDeadline(Deadline.after(GRPC_DEADLINE_SECONDS, TimeUnit.SECONDS));
    }
    public FeatureStoreServiceGrpc.FeatureStoreServiceBlockingStub featurestoreStub() {
        if (featurestoreCh == null) featurestoreCh = ManagedChannelBuilder.forAddress(featurestoreHost, featurestorePort).usePlaintext().build();
        return FeatureStoreServiceGrpc.newBlockingStub(featurestoreCh).withDeadline(Deadline.after(GRPC_DEADLINE_SECONDS, TimeUnit.SECONDS));
    }
}
