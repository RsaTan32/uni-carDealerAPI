package dev.pisti.resource;

import java.net.URI;
import java.util.List;

import dev.pisti.dto.LocationRequest;
import dev.pisti.entity.Location;
import dev.pisti.repository.LocationRepository;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/locations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({ "EMPLOYEE", "ADMIN" })
public class LocationResource {

    @Inject
    LocationRepository locationRepository;

    @GET
    @PermitAll
    public List<Location> getAll() {
        return locationRepository.listAll();
    }

    @GET
    @Path("/{id}")
    @PermitAll
    public Response getById(@PathParam("id") @Positive Long id) {

        Location location = locationRepository.findById(id);

        if (location == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        return Response.ok(location).build();
    }

    @POST
    @Transactional
    @SecurityRequirement(name = "bearerAuth")
    public Response create(@NotNull @Valid LocationRequest request) {
        Location location = new Location();
        location.name = request.name().trim();
        location.city = request.city().trim();
        location.address = request.address().trim();
        locationRepository.persistAndFlush(location);

        return Response.created(URI.create("/api/locations/" + location.id)).entity(location).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    @SecurityRequirement(name = "bearerAuth")
    public Response update(@PathParam("id") @Positive Long id, @NotNull @Valid LocationRequest request) {

        Location location = locationRepository.findById(id);

        if (location == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        location.name = request.name().trim();
        location.city = request.city().trim();
        location.address = request.address().trim();
        locationRepository.flush();
        return Response.ok(location).build();
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    @SecurityRequirement(name = "bearerAuth")
    public Response delete(@PathParam("id") @Positive Long id) {

        boolean deleted = locationRepository.deleteById(id);

        if (!deleted) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        locationRepository.flush();
        return Response.noContent().build();
    }
}
