package com.wise.escrow.infrastructure.outbox;

import com.wise.escrow.application.dto.Events.ContractCancelled;
import com.wise.escrow.application.dto.Events.ContractFunded;
import com.wise.escrow.application.dto.Events.ContractSettled;
import com.wise.escrow.application.dto.Events.CounterpartyInvited;
import com.wise.escrow.application.dto.Events.WelcomeEmailRequested;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * External-notification sink. Events arrive here AFTER commit via the
 * Spring Modulith event-publication registry (the transactional outbox):
 * publications are persisted in the same transaction as the ledger write
 * and replayed until the listener completes, so no notification is lost
 * on crash between commit and dispatch.
 */
@Component
public class NotificationEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(ContractFunded e) {
        log.info("notification contract_funded contract={} buyer={} seller={}",
                e.contractId(), e.buyerId(), e.sellerId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(ContractSettled e) {
        log.info("notification contract_settled contract={} buyer={} seller={}",
                e.contractId(), e.buyerId(), e.sellerId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(ContractCancelled e) {
        log.info("notification contract_cancelled contract={} buyer={} seller={}",
                e.contractId(), e.buyerId(), e.sellerId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(CounterpartyInvited e) {
        log.info("notification counterparty_invited email={} inviter={}",
                e.email(), e.inviterLegalName());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(WelcomeEmailRequested e) {
        log.info("notification welcome_email email={} entity={}", e.email(), e.legalName());
    }
}
