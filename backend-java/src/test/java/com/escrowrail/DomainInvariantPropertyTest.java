package com.escrowrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.escrowrail.application.dto.Quote;
import com.escrowrail.application.service.QuoteCalculator;
import com.escrowrail.application.port.FxRateProvider;
import com.escrowrail.application.service.LedgerService;
import com.escrowrail.domain.exception.CurrencyMismatchException;
import com.escrowrail.domain.exception.InsufficientFundsException;
import com.escrowrail.domain.exception.InvalidStateTransitionException;
import com.escrowrail.domain.model.Account;
import com.escrowrail.domain.model.AccountType;
import com.escrowrail.domain.model.EscrowContract;
import com.escrowrail.domain.model.EscrowStatus;
import com.escrowrail.domain.model.MoneyAmounts;
import com.escrowrail.domain.model.TrackingType;
import com.escrowrail.domain.repository.AccountRepository;
import com.escrowrail.domain.repository.LedgerRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.money.MonetaryAmount;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.Size;
import org.javamoney.moneta.Money;

/**
 * Property-based invariant fuzzing. These run pure-domain — the DB-backed
 * races live in the Testcontainers suite.
 */
class DomainInvariantPropertyTest {

    private final QuoteCalculator calculator = new QuoteCalculator(
        new StaticFxRateProviderProbe()
    );

    /** Static table copy so the property stays independent of the bean. */
    static class StaticFxRateProviderProbe implements FxRateProvider {

        @Override
        public BigDecimal getRate(String source, String target) {
            if (source.equals(target)) {
                return BigDecimal.ONE;
            }
            if ("USD".equals(source) && "EUR".equals(target)) {
                return new BigDecimal("0.9259259259259259");
            }
            if ("EUR".equals(source) && "USD".equals(target)) {
                return new BigDecimal("1.08");
            }
            throw new UnsupportedOperationException();
        }
    }

    @Provide
    Arbitrary<BigDecimal> amounts() {
        return Arbitraries.bigDecimals()
            .between(new BigDecimal("0.01"), new BigDecimal("1000000"))
            .ofScale(2);
    }

    /** The quoted deposit always covers the target AND is the minimal cent deposit that does so. */
    @Property
    void quotedDepositAlwaysCoversTarget(@ForAll("amounts") BigDecimal target) {
        assertMinimalCoveringQuote("USD", "EUR", target);
        assertMinimalCoveringQuote("EUR", "USD", target);
    }

    private void assertMinimalCoveringQuote(
        String source,
        String targetCurrency,
        BigDecimal target
    ) {
        Quote q = calculator.quote(source, targetCurrency, target);
        BigDecimal converted = q
            .principal()
            .multiply(q.midMarketRate())
            .setScale(2, RoundingMode.HALF_EVEN);
        assertThat(converted)
            .as("converted for target %s (%s->%s)", target, source, targetCurrency)
            .isGreaterThanOrEqualTo(target);
        assertThat(q.platformFee()).isPositive();
        assertThat(q.totalDeposit()).isEqualTo(q.principal().add(q.platformFee()));

        // Minimality invariant: one cent less strictly fails to cover target.
        BigDecimal oneCentLess = q.totalDeposit().subtract(MoneyAmounts.CENT);
        BigDecimal prevFee = oneCentLess
            .multiply(QuoteCalculator.CONVERSION_FEE_RATE)
            .setScale(2, RoundingMode.HALF_EVEN)
            .max(MoneyAmounts.CENT);
        BigDecimal prevConverted = oneCentLess
            .subtract(prevFee)
            .multiply(q.midMarketRate())
            .setScale(2, RoundingMode.HALF_EVEN);
        assertThat(prevConverted)
            .as("one cent less than quoted deposit %s for target %s", q.totalDeposit(), target)
            .isLessThan(target);
    }

    /** In-memory repo fakes — enough surface for LedgerService.transfer. */
    static class FakeAccounts implements AccountRepository {

        private final java.util.Map<UUID, Account> store =
            new java.util.HashMap<>();

        void put(Account a) {
            store.put(a.id(), a);
        }

        @Override
        public Optional<Account> findById(UUID id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public Optional<Account> findByIdForUpdate(UUID id) {
            return findById(id);
        }

        @Override
        public Optional<Account> findByEntityIdAndTypeAndCurrency(
            UUID entityId,
            AccountType type,
            String currency
        ) {
            return Optional.empty();
        }

        @Override
        public Optional<Account> findByEntityIdAndTypeAndCurrencyForUpdate(
            UUID entityId,
            AccountType type,
            String currency
        ) {
            return Optional.empty();
        }

        @Override
        public Optional<UUID> findAccountId(
            UUID entityId,
            AccountType type,
            String currency
        ) {
            return Optional.empty();
        }

        @Override
        public Optional<Account> findSystemAccount(
            AccountType type,
            String currency
        ) {
            return Optional.empty();
        }

        @Override
        public Optional<UUID> findSystemAccountId(
            AccountType type,
            String currency
        ) {
            return Optional.empty();
        }

        @Override
        public Optional<Account> findSystemAccountForUpdate(
            AccountType type,
            String currency
        ) {
            return Optional.empty();
        }

        @Override
        public List<Account> findAllByEntityId(UUID entityId) {
            return List.of();
        }

        @Override
        public List<Account> findSystemAccounts(
            com.escrowrail.domain.model.AccountType type
        ) {
            return List.of();
        }

        @Override
        public Account save(Account account) {
            store.put(account.id(), account);
            return account;
        }
    }

    static class FakeLedger implements LedgerRepository {

        final List<LedgerEntry> entries = new java.util.ArrayList<>();

        @Override
        public LedgerEntry record(
            UUID debitAccountId,
            UUID creditAccountId,
            BigDecimal amount,
            String currency,
            String description,
            UUID contractId
        ) {
            LedgerEntry e = new LedgerEntry(
                UUID.randomUUID(),
                debitAccountId,
                creditAccountId,
                amount,
                currency,
                description,
                contractId,
                Instant.now(),
                "test-hash"
            );
            entries.add(e);
            return e;
        }

        @Override
        public List<LedgerEntry> findByContractIdOrderByCreatedAt(
            UUID contractId
        ) {
            return entries;
        }

        @Override
        public List<LedgerEntry> findByAccountId(UUID accountId) {
            return entries;
        }
    }

    /** sum(debits) == sum(credits) across arbitrary transfer sequences —
     * exercised through the real LedgerService.transfer path. */
    @Property
    void ledgerConservationAcrossRandomSequences(
        @ForAll @Size(max = 50) List<@BigRange(
            min = "0.01",
            max = "500"
        ) BigDecimal> amounts
    ) {
        FakeAccounts accounts = new FakeAccounts();
        FakeLedger ledger = new FakeLedger();
        LedgerService service = new LedgerService(accounts, ledger);
        Account vault = new Account(
            UUID.randomUUID(),
            null,
            AccountType.ESCROW_VAULT,
            MoneyAmounts.zero("USD")
        );
        Account clearing = new Account(
            UUID.randomUUID(),
            null,
            AccountType.ESCROWRAIL_CLEARING,
            MoneyAmounts.zero("USD")
        );
        accounts.put(vault);
        accounts.put(clearing);
        for (BigDecimal a : amounts) {
            service.transfer(
                clearing.id(),
                vault.id(),
                Money.of(a, "USD"),
                "leg",
                null
            );
        }
        // Every recorded leg is one balanced debit->credit row.
        BigDecimal debited = ledger.entries
            .stream()
            .filter(e -> e.debitAccountId().equals(clearing.id()))
            .map(LedgerRepository.LedgerEntry::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credited = ledger.entries
            .stream()
            .filter(e -> e.creditAccountId().equals(vault.id()))
            .map(LedgerRepository.LedgerEntry::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debited).isEqualByComparingTo(credited);
        // The balances mirror the ledger: vault holds the total, clearing
        // holds the exact offset.
        assertThat(
            vault.balance().getNumber().numberValue(BigDecimal.class)
        ).isEqualByComparingTo(credited);
        assertThat(
            clearing.balance().getNumber().numberValue(BigDecimal.class)
        ).isEqualByComparingTo(debited.negate());
    }

    /** escrowedAmount is a persisted snapshot — it never drifts after funding. */
    @Property
    void escrowedAmountNeverDrifts(@ForAll("amounts") BigDecimal escrowed) {
        EscrowContract c = EscrowContract.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            Money.of(new BigDecimal("1000.00"), "EUR"),
            "USD",
            TrackingType.GITHUB_PR,
            "key"
        );
        c.fund(
            UUID.randomUUID(),
            Money.of(escrowed, "USD"),
            Money.of(new BigDecimal("1000.00"), "EUR"),
            new BigDecimal("0.9259"),
            UUID.randomUUID(),
            Instant.now()
        );
        assertThat(
            c.escrowedAmount().getNumber().numberValue(BigDecimal.class)
        ).isEqualByComparingTo("1000.00");
        c.settle();
        assertThat(c.status()).isEqualTo(EscrowStatus.SETTLED);
        assertThat(
            c.escrowedAmount().getNumber().numberValue(BigDecimal.class)
        ).isEqualByComparingTo("1000.00"); // unchanged by settlement
    }

    @Property
    void nonClearingAccountNeverGoesNegative(
        @ForAll("amounts") BigDecimal opening,
        @ForAll("amounts") BigDecimal debit
    ) {
        Account user = new Account(
            UUID.randomUUID(),
            UUID.randomUUID(),
            AccountType.USER,
            Money.of(opening, "USD")
        );
        MonetaryAmount d = Money.of(debit, "USD");
        if (debit.compareTo(opening) > 0) {
            assertThatThrownBy(() -> user.debit(d)).isInstanceOf(
                InsufficientFundsException.class
            );
            assertThat(
                user.balance().getNumber().numberValue(BigDecimal.class)
            ).isEqualByComparingTo(opening);
        } else {
            user.debit(d);
            assertThat(user.balance().isNegative()).isFalse();
        }
    }

    @Property
    void crossCurrencyArithmeticRejected(
        @ForAll("amounts") BigDecimal a,
        @ForAll("amounts") BigDecimal b
    ) {
        Account usd = new Account(
            UUID.randomUUID(),
            UUID.randomUUID(),
            AccountType.USER,
            Money.of(a, "USD")
        );
        assertThatThrownBy(() -> usd.credit(Money.of(b, "EUR"))).isInstanceOf(
            CurrencyMismatchException.class
        );
        assertThatThrownBy(() -> usd.debit(Money.of(b, "EUR"))).isInstanceOf(
            CurrencyMismatchException.class
        );
    }

    @Property
    void illegalTransitionsMutateNothing(
        @ForAll("amounts") BigDecimal escrowed
    ) {
        EscrowContract c = EscrowContract.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            Money.of(escrowed, "EUR"),
            "USD",
            TrackingType.SHIPPING_CARRIER,
            "k"
        );
        assertThatThrownBy(c::settle).isInstanceOf(
            InvalidStateTransitionException.class
        );
        assertThat(c.status()).isEqualTo(EscrowStatus.INITIATED);
        assertThat(c.escrowedAmount()).isNull();
    }
}
