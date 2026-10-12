package com.escrowrail.escrow.infrastructure.web;

import com.escrowrail.escrow.infrastructure.web.dto.escrow.DirectoryMemberResponse;
import com.escrowrail.escrow.application.service.DirectoryService;
import com.escrowrail.escrow.infrastructure.security.TenantPrincipal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Member directory — the common registry every verified member can search to
 * find a real counterparty before opening an escrow trade.
 */
@RestController
@RequestMapping("/api/v1/directory")
public class DirectoryController {

    private final DirectoryService service;

    /** Creates a new DirectoryController. */
    public DirectoryController(DirectoryService service) {
        this.service = service;
    }

    @GetMapping
    /** Verified members matching the query by email or legal name. */
    public Map<String, List<DirectoryMemberResponse>> search(@RequestParam String query) {
        UUID me = TenantPrincipal.entityId();
        return Map.of("members", service.search(query, me).stream()
            .map(m -> new DirectoryMemberResponse(m.email(), m.entityId(), m.legalName(), m.country()))
            .toList());
    }
}
