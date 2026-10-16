package com.escrowrail.application.service;

import com.escrowrail.domain.exception.NotFoundException;
import com.escrowrail.domain.model.Account;
import com.escrowrail.domain.model.MoneyAmounts;
import com.escrowrail.domain.repository.AccountRepository;
import com.escrowrail.domain.repository.LedgerRepository;
import com.escrowrail.domain.repository.LedgerRepository.LedgerEntry;
import java.util.UUID;
import javax.money.MonetaryAmount;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Double-entry ledger: one balanced debit->credit row per movement, both account rows under PESSIMISTIC_WRITE so transfers serialize on rows. */
@Service
public class LedgerService {

    private final AccountRepository accounts;
    private final LedgerRepository ledger;

    public LedgerService(AccountRepository accounts, LedgerRepository ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    /** Balanced transfer between distinct accounts; rounds HALF_EVEN to cents. */
    @Transactional(
        propagation = Propagation.REQUIRED,
        isolation = Isolation.READ_COMMITTED
    )
    /** {@inheritDoc} */
    public LedgerEntry transfer(
        UUID debitAccountId,
        UUID creditAccountId,
        MonetaryAmount amount,
        String description,
        UUID contractId
    ) {
        if (debitAccountId.equals(creditAccountId)) {
            throw new IllegalArgumentException(
                "Debit and credit account must differ: " + debitAccountId
            );
        }
        MonetaryAmount cents = MoneyAmounts.cents(amount);
        // Lock in a deterministic order to avoid deadlocks between legs.
        UUID first =
            debitAccountId.compareTo(creditAccountId) < 0
                ? debitAccountId
                : creditAccountId;
        UUID second = first.equals(debitAccountId)
            ? creditAccountId
            : debitAccountId;
        accounts.findByIdForUpdate(first).orElseThrow(() -> missing(first));
        accounts.findByIdForUpdate(second).orElseThrow(() -> missing(second));

        Account debit = accounts
            .findById(debitAccountId)
            .orElseThrow(() -> missing(debitAccountId));
        Account credit = accounts
            .findById(creditAccountId)
            .orElseThrow(() -> missing(creditAccountId));
        debit.debit(cents);
        credit.credit(cents);
        accounts.save(debit);
        accounts.save(credit);
        return ledger.record(
            debitAccountId,
            creditAccountId,
            cents.getNumber().numberValue(java.math.BigDecimal.class),
            cents.getCurrency().getCurrencyCode(),
            description,
            contractId
        );
    }

    /** PESSIMISTIC_WRITE-locks distinct accounts in ascending UUID order (deadlock-safe); locks hold to transaction end — callers need one. */
    @Transactional(propagation = Propagation.REQUIRED)
    public java.util.Map<UUID, com.escrowrail.domain.model.Account> lockInOrder(
        java.util.Collection<UUID> accountIds
    ) {
        java.util.Map<UUID, com.escrowrail.domain.model.Account> locked =
            new java.util.HashMap<>();
        accountIds
            .stream()
            .distinct()
            .sorted()
            .forEach(id ->
                locked.put(
                    id,
                    accounts
                        .findByIdForUpdate(id)
                        .orElseThrow(() -> missing(id))
                )
            );
        return locked;
    }

    private static NotFoundException missing(UUID id) {
        return new NotFoundException("Account not found: " + id);
    }
}
