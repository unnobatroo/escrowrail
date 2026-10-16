package com.escrowrail.application.service;

import com.escrowrail.application.event.ContractSettled;
import com.escrowrail.domain.exception.InsufficientFundsException;
import com.escrowrail.domain.exception.InvalidStateTransitionException;
import com.escrowrail.domain.exception.NotFoundException;
import com.escrowrail.domain.model.Account;
import com.escrowrail.domain.model.AccountType;
import com.escrowrail.domain.model.EscrowContract;
import com.escrowrail.domain.model.EscrowStatus;
import com.escrowrail.domain.model.MoneyAmounts;
import com.escrowrail.domain.repository.AccountRepository;
import com.escrowrail.domain.repository.EscrowContractRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Settlement leg: webhook-driven vault release to the seller. */
@Service
public class EscrowSettlementService {

    /** Settlement take rate, taken in target currency from the vault. */
    public static final BigDecimal SETTLEMENT_FEE_RATE = new BigDecimal(
        "0.008"
    );

    private final EscrowContractRepository contracts;
    private final AccountRepository accounts;
    private final EscrowAccounts escrowAccounts;
    private final LedgerService ledgerService;
    private final ApplicationEventPublisher events;

    public EscrowSettlementService(
        EscrowContractRepository contracts,
        AccountRepository accounts,
        EscrowAccounts escrowAccounts,
        LedgerService ledgerService,
        ApplicationEventPublisher events
    ) {
        this.contracts = contracts;
        this.accounts = accounts;
        this.escrowAccounts = escrowAccounts;
        this.ledgerService = ledgerService;
        this.events = events;
    }

    /** Webhook settlement: 0.8% to the fee account, remainder to the seller. Idempotent — a repeat call on SETTLED returns it unchanged, so webhook redelivery never double-pays. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EscrowContract settleMilestone(UUID contractId) {
        EscrowContract contract = contracts
            .findByIdForUpdate(contractId)
            .orElseThrow(() ->
                new NotFoundException("Contract not found: " + contractId)
            );
        if (contract.status() == EscrowStatus.SETTLED) {
            return contract; // idempotent replay
        }
        if (contract.status() != EscrowStatus.FUNDED) {
            throw new InvalidStateTransitionException(
                "Cannot settle contract %s in status %s".formatted(
                    contractId,
                    contract.status()
                )
            );
        }

        UUID sellerAccountId = accounts
            .findAccountId(
                contract.sellerId(),
                AccountType.USER,
                contract.targetCurrency()
            )
            .orElseThrow(() ->
                new NotFoundException("Seller account not found")
            );
        UUID feeAccountId = escrowAccounts.systemAccountId(
            AccountType.ESCROWRAIL_FEE,
            contract.targetCurrency()
        );
        var locked = escrowAccounts.lockAll(
            List.of(contract.vaultAccountId(), sellerAccountId, feeAccountId)
        );
        Account vault = locked.get(contract.vaultAccountId());
        Account sellerAccount = locked.get(sellerAccountId);
        Account feeAccount = locked.get(feeAccountId);

        BigDecimal escrowed = contract
            .escrowedAmount()
            .getNumber()
            .numberValue(BigDecimal.class);
        BigDecimal fee = escrowed
            .multiply(SETTLEMENT_FEE_RATE)
            .setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal payout = escrowed.subtract(fee);

        UUID cid = contract.id();
        ledgerService.transfer(
            vault.id(),
            feeAccount.id(),
            MoneyAmounts.of(fee, contract.targetCurrency()),
            "Settlement fee (0.8%)",
            cid
        );
        ledgerService.transfer(
            vault.id(),
            sellerAccount.id(),
            MoneyAmounts.of(payout, contract.targetCurrency()),
            "Seller payout",
            cid
        );

        contract.settle();
        EscrowContract saved = contracts.save(contract);
        events.publishEvent(
            new ContractSettled(cid, contract.buyerId(), contract.sellerId())
        );
        return saved;
    }
}
