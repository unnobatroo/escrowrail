package com.escrowrail.application.service;

import com.escrowrail.domain.model.Account;
import com.escrowrail.domain.model.AccountType;
import com.escrowrail.domain.repository.AccountRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Account lookups shared by the escrow lifecycle mutations. */
@Component
class EscrowAccounts {

    private final AccountRepository accounts;
    private final LedgerService ledgerService;

    EscrowAccounts(AccountRepository accounts, LedgerService ledgerService) {
        this.accounts = accounts;
        this.ledgerService = ledgerService;
    }

    /**
     * System account id, unlocked — callers lock via {@link #lockAll} in
     * global order.
     *
     * @throws IllegalStateException if the system account is missing
     */
    UUID systemAccountId(AccountType type, String currency) {
        // System accounts are seeded by V1; creating one here would race.
        return accounts
            .findSystemAccountId(type, currency)
            .orElseThrow(() ->
                new IllegalStateException(
                    "System account missing: " + type + "/" + currency
                )
            );
    }

    Map<UUID, Account> lockAll(List<UUID> ids) {
        return ledgerService.lockInOrder(ids);
    }
}
