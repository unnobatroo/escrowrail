package com.wise.escrow.application.service;

import com.wise.escrow.domain.exception.NotFoundException;
import com.wise.escrow.domain.model.Account;
import com.wise.escrow.domain.model.MoneyAmounts;
import com.wise.escrow.domain.repository.AccountRepository;
import com.wise.escrow.domain.repository.LedgerRepository;
import com.wise.escrow.domain.repository.LedgerRepository.LedgerEntry;
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

    public LedgerService(AccountRepository accounts, LedgerRepository ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
    public LedgerEntry transfer(
            UUID debitAccountId,
            UUID creditAccountId,
            MonetaryAmount amount,
            String description,
            UUID contractId) {
        MonetaryAmount cents = MoneyAmounts.cents(amount);
        // Lock in a deterministic order to avoid deadlocks between legs.
        UUID first = debitAccountId.compareTo(creditAccountId) < 0 ? debitAccountId : creditAccountId;
        UUID second = first.equals(debitAccountId) ? creditAccountId : debitAccountId;
        accounts.findByIdForUpdate(first).orElseThrow(() -> missing(first));
        accounts.findByIdForUpdate(second).orElseThrow(() -> missing(second));

        Account debit = accounts.findById(debitAccountId).orElseThrow(() -> missing(debitAccountId));
        Account credit = accounts.findById(creditAccountId).orElseThrow(() -> missing(creditAccountId));
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
                contractId);
    }

    private static NotFoundException missing(UUID id) {
        return new NotFoundException("Account not found: " + id);
    }
}
