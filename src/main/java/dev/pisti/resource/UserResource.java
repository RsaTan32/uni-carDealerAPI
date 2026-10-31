package dev.pisti.resource;

import java.net.URI;
import java.util.List;

import dev.pisti.dto.ApiViews.UserView;
import dev.pisti.dto.AuthRequests;
import dev.pisti.entity.Role;
import dev.pisti.entity.User;
import dev.pisti.repository.UserRepository;
import dev.pisti.service.AuthService;
import dev.pisti.service.CurrentUser;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/users")
@RolesAllowed("ADMIN")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class UserResource {
    @Inject
    UserRepository users;

    @Inject
    AuthService auth;

    @Inject
    CurrentUser currentUser;

    @GET
    public List<UserView> list() {
        return users.listAll().stream().map(UserView::from).toList();
    }

    @GET
    @Path("/{id}")
    public UserView getById(@PathParam("id") @Positive Long id) {
        User user = users.findById(id);
        if (user == null) {
            throw new NotFoundException();
        }
        return UserView.from(user);
    }

    @POST
    public Response create(@NotNull @Valid AuthRequests.CreateAccount request) {
        UserView user = UserView.from(auth.createAccount(request));
        return Response.created(URI.create("/api/users/" + user.id())).entity(user).build();
    }

    @PUT
    @Path("/{id}/access")
    @Transactional
    public UserView updateAccess(@PathParam("id") @Positive Long id, @NotNull @Valid AuthRequests.Access request) {
        User user = users.findById(id, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (user == null) {
            throw new NotFoundException();
        }
        if (currentUser.account().id.equals(id)) {
            throw new BadRequestException("An administrator cannot change their own access");
        }
        if (request.role() == Role.CUSTOMER && user.customer == null) {
            throw new BadRequestException("A customer account requires a linked customer profile");
        }
        user.role = request.role();
        user.enabled = request.enabled();
        user.tokenVersion++;
        return UserView.from(user);
    }
}