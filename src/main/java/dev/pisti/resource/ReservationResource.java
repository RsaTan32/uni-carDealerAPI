package dev.pisti.resource;

import java.net.URI;
import java.util.List;

import dev.pisti.dto.ReservationRequest;
import dev.pisti.dto.ReservationStatusRequest;
import dev.pisti.dto.ApiViews.ReservationView;
import dev.pisti.service.ReservationService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/reservations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({ "CUSTOMER", "EMPLOYEE", "ADMIN" })
public class ReservationResource {

    @Inject
    ReservationService reservations;

    @GET
    public List<ReservationView> getAll() {
        return reservations.list();
    }

    @GET
    @Path("/{id}")
    public ReservationView getById(@PathParam("id") @Positive Long id) {
        return ReservationView.from(reservations.findAccessible(id));
    }

    @POST
    public Response create(@NotNull @Valid ReservationRequest request) {
        ReservationView reservation = reservations.create(request);
        return Response.created(URI.create("/api/reservations/" + reservation.id())).entity(reservation).build();
    }

    @PUT
    @Path("{id}")
    public ReservationView update(@PathParam("id") @Positive Long id, @NotNull @Valid ReservationRequest request) {
        return reservations.update(id, request);
    }

    @POST
    @Path("/{id}/cancel")
    @Consumes(MediaType.WILDCARD)
    public ReservationView cancel(@PathParam("id") @Positive Long id) {
        return reservations.cancel(id);
    }

    @PATCH
    @Path("/{id}/status")
    @RolesAllowed({ "EMPLOYEE", "ADMIN" })
    public ReservationView changeStatus(@PathParam("id") @Positive Long id,
            @NotNull @Valid ReservationStatusRequest request) {
        return reservations.changeStatus(id, request.status());
    }

    @DELETE
    @Path("/{id}")
    @RolesAllowed({ "EMPLOYEE", "ADMIN" })
    public Response delete(@PathParam("id") @Positive Long id) {
        reservations.delete(id);
        return Response.noContent().build();
    }
}
