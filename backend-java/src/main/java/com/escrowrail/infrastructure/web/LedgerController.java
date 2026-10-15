package com.escrowrail.infrastructure.web;

import com.escrowrail.infrastructure.web.dto.ledger.LedgerTransactionResponse;
import com.escrowrail.domain.repository.AccountRepository;
import com.escrowrail.domain.repository.LedgerRepository;
import com.escrowrail.infrastructure.security.TenantPrincipal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ledger feed: newest journal rows across the caller's accounts, each with
 * its SHA-256 chain hash.
 */
@RestController
@RequestMapping("/api/v1/ledger")
public class LedgerController {

    private static final int FEED_LIMIT = 25;

    private final AccountRepository accounts;
    private final LedgerRepository ledger;
    public LedgerController(AccountRepository accounts, LedgerRepository ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    @GetMapping("/transactions")
    /** Latest ledger entries across all of the caller's accounts, newest first. */
    public List<LedgerTransactionResponse> transactions() {
        var myAccounts = accounts.findAllByEntityId(TenantPrincipal.entityId());
        var dedup = new LinkedHashMap<java.util.UUID, LedgerRepository.LedgerEntry>();
        myAccounts.stream()
                .flatMap(a -> ledger.findByAccountId(a.id()).stream())
                .forEach(e -> dedup.putIfAbsent(e.id(), e));
        return dedup.values().stream()
                .sorted(Comparator.comparing(LedgerRepository.LedgerEntry::createdAt).reversed())
                .limit(FEED_LIMIT)
                .map(LedgerTransactionResponse::of)
                .toList();
    }
}
