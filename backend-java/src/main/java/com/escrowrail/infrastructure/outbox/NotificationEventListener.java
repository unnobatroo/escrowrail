package com.escrowrail.infrastructure.outbox;

import com.escrowrail.application.event.ContractCancelled;
import com.escrowrail.application.event.ContractFunded;
import com.escrowrail.application.event.ContractSettled;
import com.escrowrail.application.event.CounterpartyInvited;
import com.escrowrail.application.event.WelcomeEmailRequested;
import com.escrowrail.application.port.VerificationEmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Notification sink via @TransactionalEventListener — in-memory; a crash between commit and dispatch loses the event. Durability: Modulith @ApplicationModuleListener. */
@Component
public class NotificationEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);

    private final VerificationEmailSender mailer;

    NotificationEventListener(VerificationEmailSender mailer) {
        this.mailer = mailer;
    }

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
                mask(e.email()), e.inviterLegalName());
        mailer.sendCounterpartyInvite(e.email(), e.inviterLegalName());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(WelcomeEmailRequested e) {
        log.info("notification welcome_email email={} entity={}", mask(e.email()), e.legalName());
        mailer.sendWelcomeEmail(e.email(), e.legalName());
    }

    /** Emails are PII — keep only the domain in logs. */
    private static String mask(String email) {
        int at = email.indexOf('@');
        return at < 0 ? "***" : "***@" + email.substring(at + 1);
    }
}
