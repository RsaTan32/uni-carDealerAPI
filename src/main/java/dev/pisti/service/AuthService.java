package dev.pisti.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import dev.pisti.dto.ApiViews.Token;
import dev.pisti.dto.ApiViews.UserView;
import dev.pisti.dto.AuthRequests;
import dev.pisti.entity.Customer;
import dev.pisti.entity.Role;
import dev.pisti.entity.User;
import dev.pisti.repository.CustomerRepository;
import dev.pisti.repository.UserRepository;
import io.quarkus.elytron.security.common.BcryptUtil;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.WebApplicationException;

@ApplicationScoped
public class AuthService {
    private static final String DUMMY_HASH = BcryptUtil.bcryptHash("invalid-login-placeholder");

    @Inject
    UserRepository users;

    @Inject
    CustomerRepository customers;

    @ConfigProperty(name = "mp.jwt.verify.issuer")
    String issuer;

    @ConfigProperty(name = "app.auth.token-lifetime-seconds", defaultValue = "900")
    long tokenLifetime;

    @Transactional
    public User register(AuthRequests.Register request) {
        String email = normalizeEmail(request.email());
        ensureEmailAvailable(email);
        if (customers.count("lower(email) = ?1 or drivingLicenseNumber = ?2", email,
                request.drivingLicenseNumber().trim()) > 0) {
            throw new WebApplicationException("Customer already exists; contact an employee to link an account", 409);
        }
        Customer customer = new Customer();
        customer.firstName = request.firstName().trim();
        customer.lastName = request.lastName().trim();
        customer.email = email;
        customer.drivingLicenseNumber = request.drivingLicenseNumber().trim();
        customer.phone = request.phone();
        customer.dateOfBirth = request.dateOfBirth();
        customer.drivingLicenseExpiry = request.drivingLicenseExpiry();
        customer.address = request.address();
        customer.createdAt = LocalDateTime.now();
        customers.persist(customer);

        User user = new User();
        user.email = email;
        user.passwordHash = hashPassword(request.password());
        user.role = Role.CUSTOMER;
        user.customer = customer;
        users.persistAndFlush(user);
        return user;
    }

    public Token login(AuthRequests.Login request) {
        User user = users.findByEmail(normalizeEmail(request.email()));
        boolean matches = BcryptUtil.matches(request.password(), user == null ? DUMMY_HASH : user.passwordHash);
        if (!matches || user == null || !user.enabled) {
            throw new NotAuthorizedException("Invalid email or password", "Bearer");
        }
        long now = Instant.now().getEpochSecond();
        String token = Jwt.issuer(issuer)
                .subject(user.id.toString())
                .upn(user.id.toString())
                .audience("car-rental-api")
                .groups(Set.of(user.role.name()))
                .claim("tokenVersion", user.tokenVersion)
                .issuedAt(now)
                .expiresAt(now + tokenLifetime)
                .sign();
        return new Token(token, "Bearer", tokenLifetime, UserView.from(user));
    }

    @Transactional
    public User createAccount(AuthRequests.CreateAccount request) {
        String email = normalizeEmail(request.email());
        ensureEmailAvailable(email);
        User user = new User();
        if (request.customerId() != null) {
            user.customer = customers.findById(request.customerId());
            if (user.customer == null) {
                throw new BadRequestException("Customer not found");
            }
            if (users.count("customer.id", request.customerId()) > 0) {
                throw new WebApplicationException("Customer already linked to an account", 409);
            }
        }
        if (request.role() == Role.CUSTOMER && user.customer == null) {
            throw new BadRequestException("A customer account requires a linked customer profile");
        }
        user.email = email;
        user.passwordHash = hashPassword(request.password());
        user.role = request.role();
        users.persistAndFlush(user);
        return user;
    }

    public String hashPassword(String password) {
        int length = password.getBytes(StandardCharsets.UTF_8).length;
        if (password.length() < 12 || length > 72) {
            throw new BadRequestException("Password must contain at least 12 characters and at most 72 UTF-8 bytes");
        }
        return BcryptUtil.bcryptHash(password, 12);
    }

    private void ensureEmailAvailable(String email) {
        if (users.findByEmail(email) != null) {
            throw new WebApplicationException("Email already registered", 409);
        }
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}