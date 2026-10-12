package com.escrowrail.escrow.application.service;

import com.escrowrail.escrow.domain.exception.NotFoundException;
import com.escrowrail.escrow.domain.model.Account;
import com.escrowrail.escrow.domain.model.MoneyAmounts;
import com.escrowrail.escrow.domain.repository.AccountRepository;
import com.escrowrail.escrow.domain.repository.LedgerRepository;
import com.escrowrail.escrow.domain.repository.LedgerRepository.LedgerEntry;
import java.util.UUID;
import javax.money.MonetaryAmount;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Double-entry ledger. Every movement is one balanced debit->credit row.
 * Both account rows are taken under PESSIMISTIC_WRITE so concurrent
 * transfers serialize on the rows, never on the app.
 */
@Service
public class LedgerService {

    private final AccountRepository accounts;
    private final LedgerRepository ledger;

    /** Creates a new LedgerService. */
    public LedgerService(AccountRepository accounts, LedgerRepository ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    /**
     * Transfers an amount between distinct accounts and returns the recorded ledger
     * entry. Rounds to cents using HALF_EVEN before checking balances and currencies.
     * Account updates and the ledger entry share a transaction.
     *
     * @param contractId associated escrow contract, or null for an unrelated transfer
     * @throws IllegalArgumentException if the accounts are identical or the rounded amount is negative
     * @throws NotFoundException if either account is missing
     * @throws com.escrowrail.escrow.domain.exception.CurrencyMismatchException if either account's currency differs
     * @throws com.escrowrail.escrow.domain.exception.InsufficientFundsException if a non-clearing debit account cannot cover the rounded amount
     */
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

    /**
     * Locks distinct account rows in ascending UUID order and returns the loaded
     * accounts keyed by id. Locks last until the transaction ends; callers needing
     * them for subsequent transfers must supply an enclosing transaction.
     *
     * @throws NotFoundException if any account is missing
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public java.util.Map<UUID, com.escrowrail.escrow.domain.model.Account> lockInOrder(
        java.util.Collection<UUID> accountIds
    ) {
        java.util.Map<UUID, com.escrowrail.escrow.domain.model.Account> locked =
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
