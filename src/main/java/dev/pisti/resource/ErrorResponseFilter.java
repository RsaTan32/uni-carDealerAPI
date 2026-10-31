package dev.pisti.resource;

import dev.pisti.dto.ApiError;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Provider;

@Provider
public class ErrorResponseFilter implements ContainerResponseFilter {
    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        if (response.getStatus() < 400) {
            return;
        }
        if (!(response.getEntity() instanceof ApiError)) {
            response.setEntity(ApiError.of(response.getStatus(), ApiError.defaultMessage(response.getStatus())));
        }
        response.getHeaders().putSingle(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON);
        response.getHeaders().putSingle(HttpHeaders.CACHE_CONTROL, "no-store");
    }
}