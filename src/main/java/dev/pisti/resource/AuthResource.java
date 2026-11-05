package dev.pisti.resource;

import java.net.URI;

import dev.pisti.dto.ApiViews.Token;
import dev.pisti.dto.ApiViews.UserView;
import dev.pisti.dto.AuthRequests;
import dev.pisti.service.AuthService;
import dev.pisti.service.CurrentUser;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/auth")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AuthResource {
    @Inject
    AuthService auth;

    @Inject
    CurrentUser currentUser;

    @POST
    @Path("/register")
    @PermitAll
    public Response register(@NotNull @Valid AuthRequests.Register request) {
        UserView user = UserView.from(auth.register(request));
        return Response.created(URI.create("/api/auth/me")).entity(user).build();
    }

    @POST
    @Path("/login")
    @PermitAll
    public Response login(@NotNull @Valid AuthRequests.Login request) {
        Token token = auth.login(request);
        return Response.ok(token).header("Cache-Control", "no-store").header("Pragma", "no-cache").build();
    }

    @GET
    @Path("/me")
    @RolesAllowed({ "CUSTOMER", "EMPLOYEE", "ADMIN" })
    @SecurityRequirement(name = "bearerAuth")
    public UserView me() {
        return UserView.from(currentUser.account());
    }
}