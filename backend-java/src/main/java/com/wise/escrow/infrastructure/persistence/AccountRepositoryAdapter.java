package com.wise.escrow.infrastructure.persistence;

import com.wise.escrow.domain.model.Account;
import com.wise.escrow.domain.model.AccountType;
import com.wise.escrow.domain.model.MoneyAmounts;
import com.wise.escrow.domain.repository.AccountRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class AccountRepositoryAdapter implements AccountRepository {

    private final JpaAccountRepository jpa;

    /** Creates a new AccountRepositoryAdapter. */
    public AccountRepositoryAdapter(JpaAccountRepository jpa) {
        this.jpa = jpa;
    }

    static Account toDomain(AccountEntity e) {
        return new Account(
                e.getId(), e.getEntityId(), e.getType(), MoneyAmounts.of(e.getBalance(), e.getCurrency()));
    }

    @Override
    /** {@inheritDoc} */
    public Optional<Account> findById(UUID id) {
        return jpa.findById(id).map(AccountRepositoryAdapter::toDomain);
    }

    @Override
    /** {@inheritDoc} */
    public Optional<Account> findByIdForUpdate(UUID id) {
        return jpa.findByIdForUpdate(id).map(AccountRepositoryAdapter::toDomain);
    }

    @Override
    /** {@inheritDoc} */
    public Optional<Account> findByEntityIdAndTypeAndCurrency(
            UUID entityId, AccountType type, String currency) {
        return jpa.findByEntityIdAndTypeAndCurrency(entityId, type, currency)
                .map(AccountRepositoryAdapter::toDomain);
    }

    @Override
    /** {@inheritDoc} */
    public Optional<Account> findByEntityIdAndTypeAndCurrencyForUpdate(
            UUID entityId, AccountType type, String currency) {
        return jpa.findByEntityIdAndTypeAndCurrencyForUpdate(entityId, type, currency)
                .map(AccountRepositoryAdapter::toDomain);
    }

    @Override
    /** {@inheritDoc} */
    public Optional<UUID> findAccountId(UUID entityId, AccountType type, String currency) {
        return jpa.findIdByEntityIdAndTypeAndCurrency(entityId, type, currency);
    }

    @Override
    /** {@inheritDoc} */
    public Optional<Account> findSystemAccount(AccountType type, String currency) {
        return jpa.findByEntityIdIsNullAndTypeAndCurrency(type, currency)
                .map(AccountRepositoryAdapter::toDomain);
    }

    @Override
    /** {@inheritDoc} */
    public Optional<UUID> findSystemAccountId(AccountType type, String currency) {
        return jpa.findSystemAccountId(type, currency);
    }

    @Override
    /** {@inheritDoc} */
    public Optional<Account> findSystemAccountForUpdate(AccountType type, String currency) {
        return jpa.findSystemForUpdate(type, currency).map(AccountRepositoryAdapter::toDomain);
    }

    @Override
    /** {@inheritDoc} */
    public List<Account> findAllByEntityId(UUID entityId) {
        return jpa.findAllByEntityId(entityId).stream()
                .map(AccountRepositoryAdapter::toDomain)
                .toList();
    }

    @Override
    /** {@inheritDoc} */
    public Account save(Account account) {
        // Update in place if the row is already attached; otherwise insert.
        AccountEntity entity = jpa.findById(account.id()).orElseGet(() -> {
            AccountEntity e = new AccountEntity(
                    account.id(),
                    account.entityId(),
                    account.type(),
                    account.currency(),
                    account.balance().getNumber().numberValue(java.math.BigDecimal.class));
            return e;
        });
        entity.setBalance(account.balance().getNumber().numberValue(java.math.BigDecimal.class));
        return toDomain(jpa.save(entity));
    }
}
