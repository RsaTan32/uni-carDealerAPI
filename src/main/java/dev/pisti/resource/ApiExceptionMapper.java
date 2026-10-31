package dev.pisti.resource;

import dev.pisti.dto.ApiError;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class ApiExceptionMapper implements ExceptionMapper<WebApplicationException> {
    @Override
    public Response toResponse(WebApplicationException exception) {
        Response original = exception.getResponse();
        int status = original.getStatus();
        String message = status >= 500 ? "Internal server error" : exception.getMessage();
        if (status == 400 && exception.getCause() != null) {
            message = "Malformed JSON or invalid request value";
        }
        return Response.fromResponse(original).type(MediaType.APPLICATION_JSON_TYPE)
                .entity(ApiError.of(status, message == null ? ApiError.defaultMessage(status) : message)).build();
    }
}