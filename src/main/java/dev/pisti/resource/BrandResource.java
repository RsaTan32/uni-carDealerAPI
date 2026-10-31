package dev.pisti.resource;

import java.net.URI;
import java.util.List;

import dev.pisti.dto.BrandRequest;
import dev.pisti.entity.Brand;
import dev.pisti.repository.BrandRepository;
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
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.MediaType;

@Path("/api/brands")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({ "EMPLOYEE", "ADMIN" })
public class BrandResource {

    @Inject
    BrandRepository brandRepository;

    @GET
    @PermitAll
    public List<Brand> getAll() {
        return brandRepository.listAll();
    }

    @GET
    @Path("/{id}")
    @PermitAll
    public Response getById(@PathParam("id") @Positive Long id) {
        Brand brand = brandRepository.findById(id);

        if (brand == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        return Response.ok(brand).build();
    }

    @POST
    @Transactional
    public Response create(@NotNull @Valid BrandRequest request) {
        Brand brand = new Brand();
        brand.name = request.name().trim();
        brandRepository.persistAndFlush(brand);

        return Response.created(URI.create("/api/brands/" + brand.id)).entity(brand).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public Response update(@PathParam("id") @Positive Long id, @NotNull @Valid BrandRequest request) {

        Brand brand = brandRepository.findById(id);

        if (brand == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        brand.name = request.name().trim();
        brandRepository.flush();

        return Response.ok(brand).build();

    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") @Positive Long id) {

        boolean deleted = brandRepository.deleteById(id);

        if (!deleted) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        brandRepository.flush();
        return Response.noContent().build();
    }
}
