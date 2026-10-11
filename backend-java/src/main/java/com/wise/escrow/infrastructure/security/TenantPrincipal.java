package com.escrowrail.escrow.infrastructure.security;

import com.escrowrail.escrow.domain.exception.UnauthorizedException;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Extracts the tenant + user identity from the authenticated JWT. */
public final class TenantPrincipal {

    private TenantPrincipal() {}

    /** U id. */
    public static UUID userId() {
        try {
            return UUID.fromString(jwt().getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new UnauthorizedException("Malformed subject claim");
        }
    }

    /** E id. */
    public static UUID entityId() {
        String claim = jwt().getClaimAsString("entity_id");
        if (claim == null) {
            throw new UnauthorizedException("Token missing entity_id claim");
        }
        try {
            return UUID.fromString(claim);
        } catch (IllegalArgumentException e) {
            throw new UnauthorizedException("Malformed entity_id claim");
        }
    }

    private static Jwt jwt() {
        Authentication auth =
            SecurityContextHolder.getContext().getAuthentication();
        if (
            auth instanceof JwtAuthenticationToken token &&
            token.getToken() instanceof Jwt jwt
        ) {
            return jwt;
        }
        throw new UnauthorizedException("Not authenticated");
    }
}
