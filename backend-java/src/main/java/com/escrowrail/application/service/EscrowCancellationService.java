package com.escrowrail.application.service;

import com.escrowrail.application.event.ContractCancelled;
import com.escrowrail.domain.exception.ForbiddenException;
import com.escrowrail.domain.exception.InsufficientFundsException;
import com.escrowrail.domain.exception.InvalidStateTransitionException;
import com.escrowrail.domain.exception.NotFoundException;
import com.escrowrail.domain.exception.UnsupportedCurrencyPairException;
import com.escrowrail.domain.model.Account;
import com.escrowrail.domain.model.AccountType;
import com.escrowrail.domain.model.EscrowContract;
import com.escrowrail.domain.model.EscrowStatus;
import com.escrowrail.domain.model.MoneyAmounts;
import com.escrowrail.domain.repository.EscrowContractRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.escrowrail.application.port.FxRateProvider;

/** Cancellation leg: free pre-funding, spot liquidation after funding. */
@Service
public class EscrowCancellationService {

    private final EscrowContractRepository contracts;
    private final EscrowAccounts escrowAccounts;
    private final LedgerService ledgerService;
    private final FxRateProvider fx;
    private final ApplicationEventPublisher events;

    public EscrowCancellationService(
        EscrowContractRepository contracts,
        EscrowAccounts escrowAccounts,
        LedgerService ledgerService,
        FxRateProvider fx,
        ApplicationEventPublisher events
    ) {
        this.contracts = contracts;
        this.escrowAccounts = escrowAccounts;
        this.ledgerService = ledgerService;
        this.fx = fx;
        this.events = events;
    }

    /**
     * Buyer-only cancellation: free before funding; after funding the vault
     * liquidates to source currency at current spot (buyer absorbs FX drift,
     * the conversion fee is kept). Idempotent on CANCELLED.
     *
     * @return the saved or previously cancelled contract
     * @throws NotFoundException if the contract or a required refund account is absent,
     *     or the actor is not a party
     * @throws ForbiddenException if the actor is the seller
     * @throws InvalidStateTransitionException if the contract is SETTLED
     * @throws IllegalStateException if a required system account is missing
     * @throws InsufficientFundsException if the vault cannot cover liquidation
     * @throws UnsupportedCurrencyPairException if no refund FX rate is available
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EscrowContract cancelContract(UUID contractId, UUID actorEntityId) {
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
                "Only the buyer may cancel this contract"
            );
        }
        if (contract.status() == EscrowStatus.SETTLED) {
            throw new InvalidStateTransitionException(
                "SETTLED contracts cannot be cancelled"
            );
        }
        if (contract.status() == EscrowStatus.CANCELLED) {
            return contract;
        }
        if (contract.status() == EscrowStatus.FUNDED) {
            refund(contract);
        }
        contract.cancel();
        EscrowContract saved = contracts.save(contract);
        events.publishEvent(
            new ContractCancelled(
                contract.id(),
                contract.buyerId(),
                contract.sellerId()
            )
        );
        return saved;
    }

    private void refund(EscrowContract contract) {
        UUID clearingTargetId = escrowAccounts.systemAccountId(
            AccountType.ESCROWRAIL_CLEARING,
            contract.targetCurrency()
        );
        UUID clearingSourceId = escrowAccounts.systemAccountId(
            AccountType.ESCROWRAIL_CLEARING,
            contract.sourceCurrency()
        );
        var locked = escrowAccounts.lockAll(
            List.of(
                contract.vaultAccountId(),
                contract.buyerAccountId(),
                clearingTargetId,
                clearingSourceId
            )
        );
        Account vault = locked.get(contract.vaultAccountId());
        Account clearingTarget = locked.get(clearingTargetId);
        Account clearingSource = locked.get(clearingSourceId);
        Account buyerAccount = locked.get(contract.buyerAccountId());

        BigDecimal escrowed = contract
            .escrowedAmount()
            .getNumber()
            .numberValue(BigDecimal.class);
        BigDecimal spot = fx.getRate(
            contract.targetCurrency(),
            contract.sourceCurrency()
        );
        BigDecimal refund = escrowed
            .multiply(spot)
            .setScale(2, RoundingMode.HALF_EVEN);

        UUID cid = contract.id();
        ledgerService.transfer(
            vault.id(),
            clearingTarget.id(),
            MoneyAmounts.of(escrowed, contract.targetCurrency()),
            "Vault liquidation",
            cid
        );
        ledgerService.transfer(
            clearingSource.id(),
            buyerAccount.id(),
            MoneyAmounts.of(refund, contract.sourceCurrency()),
            "Refund at current spot",
            cid
        );
    }
}
