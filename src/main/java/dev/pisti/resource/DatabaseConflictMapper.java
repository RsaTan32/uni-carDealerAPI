package dev.pisti.resource;

import java.util.Map;
import java.sql.SQLException;

import org.hibernate.exception.ConstraintViolationException;
import dev.pisti.dto.ApiError;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class DatabaseConflictMapper implements ExceptionMapper<ConstraintViolationException> {
    @Override
    public Response toResponse(ConstraintViolationException exception) {
        ApiError error = databaseError(exception);
        if (error == null) {
            error = ApiError.of(409, "Data conflicts with an existing record or database constraint");
        }
        return Response.status(error.status()).type(MediaType.APPLICATION_JSON_TYPE).entity(error).build();
    }

    static ApiError databaseError(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            String state = cause instanceof SQLException sql ? sql.getSQLState()
                    : cause instanceof ConstraintViolationException violation ? violation.getSQLState() : null;
            if (state == null) {
                continue;
            }
            switch (state) {
                case "23505":
                    return new ApiError(409, "DUPLICATE_RESOURCE", "A record with the same unique value already exists",
                            Map.of());
                case "23503":
                    return new ApiError(409, "REFERENCED_RESOURCE", "The operation conflicts with a related record",
                            Map.of());
                case "23P01":
                    return new ApiError(409, "RESERVATION_CONFLICT", "Car is already reserved for the requested period",
                            Map.of());
                case "23502", "23514", "22001", "22003":
                    return ApiError.of(400, "Data violates a database rule");
                default:
                    break;
            }
        }
        return null;
    }
}