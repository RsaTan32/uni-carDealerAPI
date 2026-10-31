package dev.pisti.service;

import java.util.Objects;

import dev.pisti.entity.Customer;
import dev.pisti.entity.Role;
import dev.pisti.entity.User;
import dev.pisti.repository.UserRepository;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAuthorizedException;

@RequestScoped
public class CurrentUser {
    @Inject
    SecurityIdentity identity;

    @Inject
    UserRepository users;

    private User account;

    public User account() {
        if (account == null) {
            if (identity.isAnonymous()) {
                throw new NotAuthorizedException("Bearer");
            }
            account = users.findById(Long.valueOf(identity.getPrincipal().getName()));
            if (account == null || !account.enabled) {
                throw new NotAuthorizedException("Bearer");
            }
        }
        return account;
    }

    public boolean isStaff() {
        return account().role == Role.EMPLOYEE || account().role == Role.ADMIN;
    }

    public Customer customer() {
        Customer customer = account().customer;
        if (customer == null) {
            throw new ForbiddenException("No customer profile linked to account");
        }
        return customer;
    }

    public boolean canAccess(Customer customer) {
        return isStaff() || Objects.equals(customer().id, customer.id);
    }
}