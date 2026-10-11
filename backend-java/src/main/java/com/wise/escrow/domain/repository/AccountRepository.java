package com.wise.escrow.domain.repository;

import com.wise.escrow.domain.model.Account;
import com.wise.escrow.domain.model.AccountType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository {
    Optional<Account> findById(UUID id);

    /** PESSIMISTIC_WRITE row lock — must be called inside a transaction. */
    Optional<Account> findByIdForUpdate(UUID id);

    Optional<Account> findByEntityIdAndTypeAndCurrency(
        UUID entityId,
        AccountType type,
        String currency
    );

    /** Locked variant of the currency lookup for funding paths. */
    Optional<Account> findByEntityIdAndTypeAndCurrencyForUpdate(
        UUID entityId,
        AccountType type,
        String currency
    );

    /**
     * Scalar id resolution for the lock-then-load pattern — does not
     * populate the persistence context, so a subsequent FOR UPDATE load
     * sees the post-lock row.
     */
    Optional<UUID> findAccountId(
        UUID entityId,
        AccountType type,
        String currency
    );

    Optional<Account> findSystemAccount(AccountType type, String currency);

    /** Scalar id variant — see findAccountId. */
    Optional<UUID> findSystemAccountId(AccountType type, String currency);

    /** Locked variant for system accounts taking part in ledger legs. */
    Optional<Account> findSystemAccountForUpdate(
        AccountType type,
        String currency
    );

    List<Account> findAllByEntityId(UUID entityId);

    Account save(Account account);
}
