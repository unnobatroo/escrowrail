package com.escrowrail.escrow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.escrowrail.escrow.application.service.AuthService;
import com.escrowrail.escrow.application.service.EscrowService;
import com.escrowrail.escrow.domain.exception.InsufficientFundsException;
import com.escrowrail.escrow.domain.exception.ZeroBalanceException;
import com.escrowrail.escrow.domain.model.Account;
import com.escrowrail.escrow.domain.model.AccountType;
import com.escrowrail.escrow.domain.model.EscrowContract;
import com.escrowrail.escrow.domain.model.MoneyAmounts;
import com.escrowrail.escrow.domain.model.TrackingType;
import com.escrowrail.escrow.domain.repository.AccountRepository;
import com.escrowrail.escrow.domain.repository.LedgerRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Zero-debt invariants: the domain aggregate refuses overdrafts, an empty
 * account gets the dedicated ZERO_BALANCE_LOCKED code, and the database
 * check constraint physically blocks corruption that bypasses the service.
 */
@SpringBootTest
class AccountBalanceSafetyTest extends AbstractPostgresIT {

    @Autowired
    AuthService auth;

    @Autowired
    EscrowService escrow;

    @Autowired
    AccountRepository accounts;

    @Autowired
    LedgerRepository ledger;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    com.escrowrail.escrow.application.service.ProfileService profiles;

    private com.escrowrail.escrow.application.service.AuthService.Registration entity(
        String tag
    ) {
        return auth.register(
            tag + System.nanoTime() + "@t.dev",
            "pw123456",
            "Safety " + tag,
            "UB-" + System.nanoTime(),
            "US"
        );
    }

    /** Onboarding deposit — the only sanctioned credit path left. */
    private void deposit(
        com.escrowrail.escrow.application.service.AuthService.Registration reg,
        String amount
    ) {
        profiles.update(
            reg.user().id(),
            reg.organization().id(),
            null,
            null,
            java.util.Map.of("USD", new BigDecimal(amount))
        );
    }

    private Account usd(UUID entityId) {
        return accounts
            .findByEntityIdAndTypeAndCurrency(entityId, AccountType.USER, "USD")
            .orElseThrow();
    }

    @Test
    void overdraftDebitIsRejectedAndRollbackKeepsBalance() {
        var reg = entity("od-");
        UUID id = reg.organization().id();
        // Registration opens empty accounts — no free sandbox money.
        assertThat(usd(id).balance().isZero()).isTrue();
        deposit(reg, "100.00");
        Account account = usd(id);
        BigDecimal balance = account
            .balance()
            .getNumber()
            .numberValue(BigDecimal.class);
        int ledgerRowsBefore = ledger.findByAccountId(account.id()).size();

        assertThatThrownBy(() ->
            account.debit(
                MoneyAmounts.of(balance.add(new BigDecimal("0.01")), "USD")
            )
        ).isInstanceOf(InsufficientFundsException.class);

        // Rejection is a no-op: the balance is untouched and nothing hit
        // the ledger — no partial state can survive.
        assertThat(
            account.balance().getNumber().numberValue(BigDecimal.class)
        ).isEqualByComparingTo(balance);
        assertThat(ledger.findByAccountId(account.id()))
            .hasSize(ledgerRowsBefore);
    }

    @Test
    void fundingFromAnEmptyAccountReturnsZeroBalanceLocked() {
        UUID buyer = entity("zb-b-").organization().id();
        UUID seller = entity("zb-s-").organization().id();
        // The buyer's USD account exists but holds nothing — the account
        // is empty by construction, no drain fixture needed.
        EscrowContract c = escrow.createContract(
            buyer,
            seller,
            MoneyAmounts.of(new BigDecimal("500.00"), "EUR"),
            "USD",
            TrackingType.GITHUB_PR,
            "zb-pr"
        );
        BigDecimal quotedTotal = escrow
            .quote("USD", "EUR", new BigDecimal("500.00"))
            .totalDeposit();

        assertThatThrownBy(() ->
            escrow.fundContract(
                c.id(),
                buyer,
                MoneyAmounts.of(quotedTotal, "USD")
            )
        )
            .isInstanceOf(ZeroBalanceException.class)
            .hasMessageContaining("empty");
    }

    @Test
    void databaseCheckConstraintBlocksDirectNegativeBalance() {
        UUID id = entity("sql-").organization().id();
        Account account = usd(id);
        // A rogue write path cannot set a user balance below zero — the
        // constraint rejects it at the row level.
        assertThatThrownBy(() ->
            jdbc.update(
                "UPDATE accounts SET balance = -1.00 WHERE id = ?",
                account.id()
            )
        ).isInstanceOf(DataIntegrityViolationException.class);
        // Clearing accounts are exempt — the platform's own clearing row
        // may go negative funding grants and FX legs.
        var clearing = accounts
            .findSystemAccountId(AccountType.ESCROWRAIL_CLEARING, "USD")
            .orElseThrow();
        jdbc.update(
            "UPDATE accounts SET balance = balance - 1.00 WHERE id = ?",
            clearing
        );
    }
}
