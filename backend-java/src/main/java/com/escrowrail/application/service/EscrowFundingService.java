package com.escrowrail.application.service;

import com.escrowrail.application.event.ContractFunded;
import com.escrowrail.application.dto.FundingResult;
import com.escrowrail.application.dto.Quote;
import com.escrowrail.domain.exception.CurrencyMismatchException;
import com.escrowrail.domain.exception.ForbiddenException;
import com.escrowrail.domain.exception.InsufficientFundsException;
import com.escrowrail.domain.exception.InvalidStateTransitionException;
import com.escrowrail.domain.exception.NotFoundException;
import com.escrowrail.domain.exception.UnsupportedCurrencyPairException;
import com.escrowrail.domain.exception.ValidationException;
import com.escrowrail.domain.exception.ZeroBalanceException;
import com.escrowrail.domain.model.Account;
import com.escrowrail.domain.model.AccountType;
import com.escrowrail.domain.model.EscrowContract;
import com.escrowrail.domain.model.EscrowStatus;
import com.escrowrail.domain.model.MoneyAmounts;
import com.escrowrail.domain.repository.AccountRepository;
import com.escrowrail.domain.repository.EscrowContractRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.money.MonetaryAmount;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.escrowrail.application.port.FxRateProvider;

/** Funding leg: buyer debit, FX conversion, vault creation. */
@Service
public class EscrowFundingService {

    private final EscrowContractRepository contracts;
    private final AccountRepository accounts;
    private final EscrowAccounts escrowAccounts;
    private final LedgerService ledgerService;
    private final QuoteCalculator quotes;
    private final FxRateProvider fx;
    private final ApplicationEventPublisher events;

    public EscrowFundingService(
        EscrowContractRepository contracts,
        AccountRepository accounts,
        EscrowAccounts escrowAccounts,
        LedgerService ledgerService,
        QuoteCalculator quotes,
        FxRateProvider fx,
        ApplicationEventPublisher events
    ) {
        this.contracts = contracts;
        this.accounts = accounts;
        this.escrowAccounts = escrowAccounts;
        this.ledgerService = ledgerService;
        this.quotes = quotes;
        this.fx = fx;
        this.events = events;
    }

    /** Funds the contract: 0.5% fee (min 0.01, HALF_EVEN) in source currency, remainder converted at spot and vaulted in target currency. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FundingResult fundContract(
        UUID contractId,
        UUID actorEntityId,
        MonetaryAmount deposit
    ) {
        EscrowContract contract = contracts
            .findByIdForUpdate(contractId)
            .orElseThrow(() ->
                new NotFoundException("Contract not found: " + contractId)
            );
        if (!contract.involves(actorEntityId)) {
            throw new NotFoundException("Contract not found: " + contractId);
        }
        if (!contract.isBuyer(actorEntityId)) {
            throw new ForbiddenException(
                "Only the buyer may fund this contract"
            );
        }
        if (contract.status() != EscrowStatus.INITIATED) {
            throw new InvalidStateTransitionException(
                "Contract %s is %s".formatted(contractId, contract.status())
            );
        }
        if (
            !deposit
                .getCurrency()
                .getCurrencyCode()
                .equals(contract.sourceCurrency())
        ) {
            throw new CurrencyMismatchException(
                "Deposit must be in " + contract.sourceCurrency()
            );
        }

        UUID buyerAccountId = accounts
            .findAccountId(
                actorEntityId,
                AccountType.USER,
                contract.sourceCurrency()
            )
            .orElseThrow(() ->
                new NotFoundException("Funding account not found")
            );
        UUID clearingSourceId = escrowAccounts.systemAccountId(
            AccountType.ESCROWRAIL_CLEARING,
            contract.sourceCurrency()
        );
        UUID clearingTargetId = escrowAccounts.systemAccountId(
            AccountType.ESCROWRAIL_CLEARING,
            contract.targetCurrency()
        );
        UUID feeAccountId = escrowAccounts.systemAccountId(
            AccountType.ESCROWRAIL_FEE,
            contract.sourceCurrency()
        );
        // Lock all touched rows in global UUID order (as LedgerService) —
        // fund vs refund would deadlock on opposite clearing accounts.
        var locked = escrowAccounts.lockAll(
            List.of(buyerAccountId, clearingSourceId, clearingTargetId, feeAccountId)
        );
        Account buyerAccount = locked.get(buyerAccountId);
        Account clearingSource = locked.get(clearingSourceId);
        Account feeAccount = locked.get(feeAccountId);

        BigDecimal depositValue = deposit
            .getNumber()
            .numberValue(BigDecimal.class);
        if (depositValue.stripTrailingZeros().scale() > 2) {
            throw new ValidationException(
                "depositAmount must have at most 2 decimal places"
            );
        }
        BigDecimal fee = depositValue
            .multiply(QuoteCalculator.CONVERSION_FEE_RATE)
            .setScale(2, RoundingMode.HALF_EVEN)
            .max(new BigDecimal("0.01"));
        BigDecimal principal = depositValue.subtract(fee);
        BigDecimal rate = fx.getRate(
            contract.sourceCurrency(),
            contract.targetCurrency()
        );
        BigDecimal converted = principal
            .multiply(rate)
            .setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal target = contract
            .targetAmount()
            .getNumber()
            .numberValue(BigDecimal.class);
        if (converted.compareTo(target) < 0) {
            throw new InsufficientFundsException(
                "Deposit %s converts to %s %s, below target %s".formatted(
                    depositValue,
                    converted,
                    contract.targetCurrency(),
                    target
                )
            );
        }
        // Over-deposit would over-vault the target — reject rather than escrow.
        Quote required = quotes.quote(
            contract.sourceCurrency(),
            contract.targetCurrency(),
            target
        );
        if (depositValue.compareTo(required.totalDeposit()) > 0) {
            throw new ValidationException(
                "Deposit %s exceeds quoted total %s".formatted(
                    depositValue,
                    required.totalDeposit()
                )
            );
        }

        // Full deposit up front; empty account gets its own code so the UI
        // can route to top-up.
        BigDecimal buyerBalance = buyerAccount
            .balance()
            .getNumber()
            .numberValue(BigDecimal.class);
        if (buyerBalance.signum() == 0) {
            throw new ZeroBalanceException(
                "Your %s account is empty — it needs %s to fund this trade"
                    .formatted(contract.sourceCurrency(), depositValue)
            );
        }
        if (buyerAccount.balance().subtract(deposit).isNegative()) {
            throw new InsufficientFundsException(
                "Balance %s cannot cover deposit %s".formatted(
                    buyerAccount.balance(),
                    deposit
                )
            );
        }

        UUID cid = contract.id();
        ledgerService.transfer(
            buyerAccount.id(),
            feeAccount.id(),
            MoneyAmounts.of(fee, contract.sourceCurrency()),
            "FX conversion fee (0.5%)",
            cid
        );
        ledgerService.transfer(
            buyerAccount.id(),
            clearingSource.id(),
            MoneyAmounts.of(principal, contract.sourceCurrency()),
            "Conversion principal",
            cid
        );

        Account vault = accounts.save(
            Account.open(
                null,
                AccountType.ESCROW_VAULT,
                contract.targetCurrency()
            )
        );
        ledgerService.transfer(
            clearingTargetId,
            vault.id(),
            MoneyAmounts.of(converted, contract.targetCurrency()),
            "Escrow vault funding",
            cid
        );

        contract.fund(
            buyerAccount.id(),
            deposit,
            MoneyAmounts.of(converted, contract.targetCurrency()),
            rate,
            vault.id(),
            Instant.now()
        );
        EscrowContract saved = contracts.save(contract);
        events.publishEvent(
            new ContractFunded(cid, contract.buyerId(), contract.sellerId())
        );
        return new FundingResult(saved, fee);
    }
}
