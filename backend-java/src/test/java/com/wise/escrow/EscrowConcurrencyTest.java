package com.wise.escrow;

import static org.assertj.core.api.Assertions.assertThat;

import com.wise.escrow.application.service.AuthService;
import com.wise.escrow.application.service.AuthService.Registration;
import com.wise.escrow.application.service.EscrowService;
import com.wise.escrow.domain.exception.InsufficientFundsException;
import com.wise.escrow.domain.model.AccountType;
import com.wise.escrow.domain.model.EscrowContract;
import com.wise.escrow.domain.model.EscrowStatus;
import com.wise.escrow.domain.model.MoneyAmounts;
import com.wise.escrow.domain.model.TrackingType;
import com.wise.escrow.domain.repository.AccountRepository;
import com.wise.escrow.domain.repository.EscrowContractRepository;
import com.wise.escrow.domain.repository.LedgerRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Race conditions against a real PostgreSQL — pessimistic row locks must
 * serialize the losers; the winners commit exactly once.
 */
class EscrowConcurrencyTest extends AbstractPostgresIT {

    @Autowired EscrowService escrow;
    @Autowired AuthService auth;
    @Autowired EscrowContractRepository contracts;
    @Autowired AccountRepository accounts;
    @Autowired LedgerRepository ledger;

    private record Parties(UUID buyer, UUID seller) {}

    private Parties parties(String tag) {
        Registration b = auth.register(
                "b-" + tag + "@t.dev", "pw123456", "Buyer " + tag, "UB-" + tag + "-B", "US");
        Registration s = auth.register(
                "s-" + tag + "@t.dev", "pw123456", "Seller " + tag, "UB-" + tag + "-S", "DE");
        return new Parties(b.organization().id(), s.organization().id());
    }

    private EscrowContract funded(Parties p, String tag, BigDecimal target) {
        EscrowContract c = escrow.createContract(
                p.buyer(), p.seller(),
                MoneyAmounts.of(target, "EUR"), "USD",
                TrackingType.GITHUB_PR, "pr-" + tag);
        EscrowService.Quote q = escrow.quote("USD", "EUR", target);
        return escrow.fundContract(
                c.id(), p.buyer(), MoneyAmounts.of(q.totalDeposit(), "USD"));
    }

    @Test
    void settlementRaceExactlyOnce() throws InterruptedException {
        Parties p = parties("race1");
        EscrowContract c = funded(p, "race1", new BigDecimal("1000.00"));
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger settled = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    ready.countDown();
                    go.await();
                    EscrowContract result = escrow.settleMilestone(c.id());
                    if (result.status() == EscrowStatus.SETTLED) settled.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
                }
            });
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(settled.get()).isEqualTo(threads); // all see SETTLED (idempotent)
        assertThat(failed.get()).isZero();

        // Seller credited exactly once; Wise fee exactly once — the ledger
        // is the arbiter, not the response codes.
        List<?> entries = ledger.findByContractIdOrderByCreatedAt(c.id());
        long payouts = entries.stream()
                .map(e -> (LedgerRepository.LedgerEntry) e)
                .filter(e -> e.description().contains("Seller payout"))
                .count();
        long fees = entries.stream()
                .map(e -> (LedgerRepository.LedgerEntry) e)
                .filter(e -> e.description().contains("Settlement fee"))
                .count();
        assertThat(payouts).isEqualTo(1);
        assertThat(fees).isEqualTo(1);
        var sellerAccount = accounts
                .findByEntityIdAndTypeAndCurrency(p.seller(), AccountType.USER, "EUR")
                .orElseThrow();
        assertThat(sellerAccount.balance().getNumber().numberValue(BigDecimal.class))
                .isEqualByComparingTo("10992.00"); // 10000 sandbox + 992 payout
    }

    @Test
    void fundingDepletionRaceNeverOverdraws() throws InterruptedException {
        Parties p = parties("race2");
        // Buyer has exactly enough for ONE funding of 40,000 EUR target.
        int threads = 5;
        EscrowContract[] cs = new EscrowContract[threads];
        for (int i = 0; i < threads; i++) {
            cs[i] = escrow.createContract(
                    p.buyer(), p.seller(),
                    MoneyAmounts.of(new BigDecimal("40000.00"), "EUR"), "USD",
                    TrackingType.GITHUB_PR, "depl-" + i);
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger funded = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        EscrowService.Quote q = escrow.quote("USD", "EUR", new BigDecimal("40000.00"));
        List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            EscrowContract c = cs[i];
            futures.add(pool.submit(() -> {
                try {
                    ready.countDown();
                    go.await();
                    escrow.fundContract(c.id(), p.buyer(), MoneyAmounts.of(q.totalDeposit(), "USD"));
                    funded.incrementAndGet();
                } catch (InsufficientFundsException e) {
                    insufficient.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        // Unexpected worker failures must surface with their real cause.
        for (var f : futures) {
            org.assertj.core.api.Assertions.assertThatCode(f::get)
                    .doesNotThrowAnyException();
        }
        assertThat(funded.get()).isEqualTo(1);
        assertThat(insufficient.get()).isEqualTo(threads - 1);

        var buyerUsd = accounts
                .findByEntityIdAndTypeAndCurrency(p.buyer(), AccountType.USER, "USD")
                .orElseThrow();
        assertThat(buyerUsd.balance().isNegative()).isFalse();
    }
}
