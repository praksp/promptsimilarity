package com.promptsimilarity.gateway;

import jakarta.annotation.Priority;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.CompletionException;

/**
 * Returns a clear JSON error message for the dashboard instead of Quarkus's default error id.
 */
@Provider
@Priority(1)
public class ErrorMapper implements ExceptionMapper<Throwable> {

    private static final Logger log = LoggerFactory.getLogger(ErrorMapper.class);

    @Override
    public Response toResponse(Throwable t) {
        Throwable unwrapped = t;
        while (unwrapped instanceof CompletionException ce && ce.getCause() != null) {
            unwrapped = ce.getCause();
        }
        String message = toMessage(unwrapped);
        log.warn("Request failed: {}", message, unwrapped);

        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of("message", message))
                .build();
    }

    private static String toMessage(Throwable t) {
        if (t == null) return "Request failed";
        String msg = t.getMessage();
        if (msg != null && (msg.contains("DEADLINE_EXCEEDED") || msg.contains("deadline was exceeded"))) {
            return "Backend service timed out. Try again in a moment; if it persists, ensure vector-service and search-service are running.";
        }
        if (t instanceof ConnectException) {
            return "Backend service unreachable (connection refused). Ensure prompt-service is running: docker compose ps";
        }
        if (t instanceof SocketTimeoutException) {
            return "Backend service did not respond in time. The embedding service may be loading; try again in a minute.";
        }
        if (t instanceof IOException) {
            return "Network error: " + (msg != null && !msg.isBlank() ? msg : "check backend services");
        }
        Throwable cause = t.getCause();
        if (cause != null && cause != t) {
            return toMessage(cause);
        }
        return msg != null ? msg : "Request failed";
    }
}
