package dev.pisti.resource;

import java.net.URI;
import java.util.List;

import dev.pisti.dto.ApiViews.CustomerView;
import dev.pisti.dto.CustomerRequest;
import dev.pisti.entity.Customer;
import dev.pisti.repository.CustomerRepository;
import dev.pisti.service.CurrentUser;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.Locale;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;

@Path("/api/customers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({ "EMPLOYEE", "ADMIN" })
@SecurityRequirement(name = "bearerAuth")
public class CustomerResource {

    @Inject
    CustomerRepository customerRepository;

    @Inject
    CurrentUser currentUser;

    @GET
    public List<CustomerView> getAll() {
        return customerRepository.listAll().stream().map(CustomerView::from).toList();
    }

    @GET
    @Path("/me")
    @RolesAllowed("CUSTOMER")
    public CustomerView me() {
        return CustomerView.from(currentUser.customer());
    }

    @GET
    @Path("/{id}")
    @RolesAllowed({ "CUSTOMER", "EMPLOYEE", "ADMIN" })
    public Response getById(@PathParam("id") @Positive Long id) {

        Customer customer = customerRepository.findById(id);

        if (customer == null || !currentUser.canAccess(customer)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        return Response.ok(CustomerView.from(customer)).build();
    }

    @POST
    @Transactional
    public Response create(@NotNull @Valid CustomerRequest request) {
        Customer customer = new Customer();
        apply(customer, request);
        customerRepository.persistAndFlush(customer);

        return Response.created(URI.create("/api/customers/" + customer.id))
                .entity(CustomerView.from(customer)).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    @RolesAllowed({ "CUSTOMER", "EMPLOYEE", "ADMIN" })
    public Response update(@PathParam("id") @Positive Long id, @NotNull @Valid CustomerRequest request) {

        Customer customer = customerRepository.findById(id);

        if (customer == null || !currentUser.canAccess(customer)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        apply(customer, request);
        customerRepository.flush();
        return Response.ok(CustomerView.from(customer)).build();
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") @Positive Long id) {

        boolean deleted = customerRepository.deleteById(id);

        if (!deleted) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        customerRepository.flush();
        return Response.noContent().build();
    }

    private void apply(Customer customer, CustomerRequest request) {
        customer.firstName = request.firstName().trim();
        customer.lastName = request.lastName().trim();
        customer.email = request.email().trim().toLowerCase(Locale.ROOT);
        customer.phone = request.phone();
        customer.dateOfBirth = request.dateOfBirth();
        customer.drivingLicenseNumber = request.drivingLicenseNumber().trim();
        customer.drivingLicenseExpiry = request.drivingLicenseExpiry();
        customer.address = request.address();
    }
}
