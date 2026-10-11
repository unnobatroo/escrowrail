package com.escrowrail.escrow.infrastructure.web;

import com.escrowrail.escrow.application.dto.ApiDtos.LedgerEntryView;
import com.escrowrail.escrow.domain.repository.AccountRepository;
import com.escrowrail.escrow.domain.repository.LedgerRepository;
import com.escrowrail.escrow.infrastructure.security.TenantPrincipal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-wide ledger feed: the latest double-entry journal rows touching any
 * of the caller's accounts, newest first. Every row carries its SHA-256 chain
 * hash so the stream is verifiable end to end.
 */
@RestController
@RequestMapping("/api/v1/ledger")
public class LedgerController {

    private static final int FEED_LIMIT = 25;

    private final AccountRepository accounts;
    private final LedgerRepository ledger;

    /** Creates a new LedgerController. */
    public LedgerController(AccountRepository accounts, LedgerRepository ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    @GetMapping("/transactions")
    /** Latest ledger entries across all of the caller's accounts, newest first. */
    public List<LedgerEntryView> transactions() {
        var myAccounts = accounts.findAllByEntityId(TenantPrincipal.entityId());
        var dedup = new LinkedHashMap<java.util.UUID, LedgerRepository.LedgerEntry>();
        myAccounts.stream()
                .flatMap(a -> ledger.findByAccountId(a.id()).stream())
                .forEach(e -> dedup.putIfAbsent(e.id(), e));
        return dedup.values().stream()
                .sorted(Comparator.comparing(LedgerRepository.LedgerEntry::createdAt).reversed())
                .limit(FEED_LIMIT)
                .map(LedgerEntryView::of)
                .toList();
    }
}
