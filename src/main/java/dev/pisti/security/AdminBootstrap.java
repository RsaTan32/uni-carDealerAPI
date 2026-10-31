package dev.pisti.security;

import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import dev.pisti.dto.AuthRequests;
import dev.pisti.entity.Role;
import dev.pisti.repository.UserRepository;
import dev.pisti.service.AuthService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Validator;

@ApplicationScoped
public class AdminBootstrap {
    private static final Logger LOG = Logger.getLogger(AdminBootstrap.class);

    @ConfigProperty(name = "app.auth.bootstrap-admin.email")
    Optional<String> email;

    @ConfigProperty(name = "app.auth.bootstrap-admin.password")
    Optional<String> password;

    @Inject
    UserRepository users;

    @Inject
    AuthService auth;

    @Inject
    Validator validator;

    @Transactional
    public void initialize(@Observes StartupEvent event) {
        if (email.isEmpty() && password.isEmpty()) {
            return;
        }
        if (email.isEmpty() || password.isEmpty()) {
            throw new IllegalStateException("Both bootstrap admin email and password must be configured");
        }
        if (users.count("role = ?1 and enabled = true", Role.ADMIN) > 0) {
            return;
        }
        AuthRequests.CreateAccount request = new AuthRequests.CreateAccount(email.get(), password.get(), Role.ADMIN,
                null);
        if (!validator.validate(request).isEmpty()) {
            throw new IllegalStateException("Invalid bootstrap admin email or password");
        }
        auth.createAccount(request);
        LOG.info("Initial administrator created; remove bootstrap credentials from the environment");
    }
}