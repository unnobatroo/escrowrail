package com.escrowrail.escrow.infrastructure.web;

import com.escrowrail.escrow.infrastructure.web.dto.profile.EntitySummaryResponse;
import com.escrowrail.escrow.infrastructure.web.dto.profile.UpdateProfileRequest;
import com.escrowrail.escrow.infrastructure.web.dto.profile.UserProfileResponse;
import com.escrowrail.escrow.application.service.ProfileService;
import com.escrowrail.escrow.domain.model.AppUser;
import com.escrowrail.escrow.domain.model.Organization;
import com.escrowrail.escrow.infrastructure.security.TenantPrincipal;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Self-service profile: legal entity details and the onboarding deposit. */
@RestController
@RequestMapping("/api/v1/profile")
public class ProfileController {

    private final ProfileService service;

    /** Creates a new ProfileController. */
    public ProfileController(ProfileService service) {
        this.service = service;
    }

    @PutMapping
    /** Updates the caller's entity profile; closes the onboarding window. */
    public UserProfileResponse update(@Valid @RequestBody UpdateProfileRequest body) {
        var result = service.update(
                TenantPrincipal.userId(),
                TenantPrincipal.entityId(),
                body.legalEntityName(),
                body.taxId(),
                body.initialDeposits());
        return toView(result.user(), result.organization());
    }

    private static UserProfileResponse toView(AppUser user, Organization org) {
        return new UserProfileResponse(
                user.id(),
                user.email(),
                user.active(),
                user.emailVerified(),
                user.status().name(),
                user.onboardingCompleted(),
                new EntitySummaryResponse(
                        org.id(), org.legalName(), org.uboTaxId().value(), org.country()),
                user.createdAt());
    }
}
