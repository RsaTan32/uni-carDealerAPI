package dev.pisti.security;

import org.eclipse.microprofile.jwt.JsonWebToken;

import dev.pisti.entity.User;
import dev.pisti.repository.UserRepository;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;

@ApplicationScoped
public class AccountIdentityAugmentor implements SecurityIdentityAugmentor {
    @Inject
    UserRepository users;

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        if (identity.isAnonymous()) {
            return Uni.createFrom().item(identity);
        }
        return context.runBlocking(() -> validateAccount(identity));
    }

    @ActivateRequestContext
    public SecurityIdentity validateAccount(SecurityIdentity identity) {
        if (!(identity.getPrincipal() instanceof JsonWebToken token)) {
            throw new AuthenticationFailedException();
        }
        try {
            User user = users.findById(Long.valueOf(token.getSubject()));
            long version = Long.parseLong(String.valueOf((Object) token.getClaim("tokenVersion")));
            if (user == null || !user.enabled || user.tokenVersion != version
                    || !token.getGroups().equals(java.util.Set.of(user.role.name()))) {
                throw new AuthenticationFailedException();
            }
            return identity;
        } catch (NumberFormatException exception) {
            throw new AuthenticationFailedException();
        }
    }
}