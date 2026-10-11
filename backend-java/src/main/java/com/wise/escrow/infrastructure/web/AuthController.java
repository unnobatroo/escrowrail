package com.escrowrail.escrow.infrastructure.web;

import com.escrowrail.escrow.application.dto.ApiDtos.EntityView;
import com.escrowrail.escrow.application.dto.ApiDtos.LoginRequest;
import com.escrowrail.escrow.application.dto.ApiDtos.RegisterRequest;
import com.escrowrail.escrow.application.dto.ApiDtos.RegisterResponse;
import com.escrowrail.escrow.application.dto.ApiDtos.TokenResponse;
import com.escrowrail.escrow.application.dto.ApiDtos.UserView;
import com.escrowrail.escrow.application.service.AuthService;
import com.escrowrail.escrow.application.service.AuthService.Registration;
import com.escrowrail.escrow.domain.model.AppUser;
import com.escrowrail.escrow.domain.model.Organization;
import com.escrowrail.escrow.domain.repository.AppUserRepository;
import com.escrowrail.escrow.domain.repository.OrganizationRepository;
import com.escrowrail.escrow.infrastructure.security.SecurityConfig.JwtIssuer;
import com.escrowrail.escrow.infrastructure.security.TenantPrincipal;
import com.escrowrail.escrow.domain.exception.NotFoundException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService auth;
    private final JwtIssuer jwt;
    private final AppUserRepository users;
    private final OrganizationRepository organizations;

    /** Creates a new AuthController. */
    public AuthController(
            AuthService auth,
            JwtIssuer jwt,
            AppUserRepository users,
            OrganizationRepository organizations) {
        this.auth = auth;
        this.jwt = jwt;
        this.users = users;
        this.organizations = organizations;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    /** R. */
    public RegisterResponse register(@Valid @RequestBody RegisterRequest body) {
        Registration reg = auth.register(
                body.email(), body.password(), body.legalName(), body.uboTaxId(), body.country());
        return new RegisterResponse(
                jwt.issue(reg.user().id(), reg.user().entityId()),
                toView(reg.user(), reg.organization()));
    }

    @PostMapping("/login")
    /** L. */
    public TokenResponse login(@Valid @RequestBody LoginRequest body) {
        AppUser user = auth.authenticate(body.email(), body.password());
        return new TokenResponse(jwt.issue(user.id(), user.entityId()), "bearer");
    }

    @GetMapping("/me")
    /** M. */
    public UserView me() {
        AppUser user = users
                .findById(TenantPrincipal.userId())
                .orElseThrow(() -> new NotFoundException("User not found"));
        Organization org = organizations
                .findById(user.entityId())
                .orElseThrow(() -> new NotFoundException("Organization not found"));
        return toView(user, org);
    }

    private static UserView toView(AppUser user, Organization org) {
        return new UserView(
                user.id(),
                user.email(),
                new EntityView(org.id(), org.legalName(), org.country()),
                user.createdAt());
    }
}
