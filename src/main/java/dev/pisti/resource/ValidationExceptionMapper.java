package dev.pisti.resource;

import java.util.Map;
import java.util.TreeMap;

import io.quarkus.hibernate.validator.runtime.jaxrs.ResteasyReactiveViolationException;

import dev.pisti.dto.ApiError;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class ValidationExceptionMapper implements ExceptionMapper<ResteasyReactiveViolationException> {
    @Override
    public Response toResponse(ResteasyReactiveViolationException exception) {
        Map<String, String> errors = new TreeMap<>();
        for (var violation : exception.getConstraintViolations()) {
            String field = "request";
            for (Path.Node node : violation.getPropertyPath()) {
                if (node.getKind() == ElementKind.RETURN_VALUE) {
                    return Response.serverError().type(MediaType.APPLICATION_JSON_TYPE)
                            .entity(ApiError.of(500, "Internal server error")).build();
                }
                if (node.getName() != null && (node.getKind() == ElementKind.PROPERTY
                        || node.getKind() == ElementKind.PARAMETER)) {
                    field = node.getName();
                }
            }
            errors.merge(field, violation.getMessage(), (first, second) -> first + "; " + second);
        }
        return Response.status(400).type(MediaType.APPLICATION_JSON_TYPE)
                .entity(new ApiError(400, "VALIDATION_ERROR", "Request validation failed", errors)).build();
    }
}