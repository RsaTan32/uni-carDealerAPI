package dev.pisti.resource;

import org.jboss.logging.Logger;

import dev.pisti.dto.ApiError;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class UnexpectedExceptionMapper implements ExceptionMapper<Exception> {
    private static final Logger LOG = Logger.getLogger(UnexpectedExceptionMapper.class);

    @Override
    public Response toResponse(Exception exception) {
        ApiError error = DatabaseConflictMapper.databaseError(exception);
        if (error == null) {
            LOG.error("Unexpected API failure", exception);
            error = ApiError.of(500, "Internal server error");
        }
        return Response.status(error.status()).type(MediaType.APPLICATION_JSON_TYPE).entity(error).build();
    }
}