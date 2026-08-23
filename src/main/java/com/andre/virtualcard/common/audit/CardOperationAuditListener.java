package com.andre.virtualcard.common.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Demonstrates audit decoupling via an AFTER_COMMIT application event:
 * rollback, technical failure, and missing-card cases therefore produce no audit,
 * and idempotency replays publish nothing because they perform no business mutation.
 * Guaranteed external delivery would require an outbox/broker design.
 */
@Component
public class CardOperationAuditListener {

    private static final Logger log = LoggerFactory.getLogger(CardOperationAuditListener.class);

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCardOperation(CardOperationAuditEvent event) {
        // Best-effort audit: the financial operation is already durably committed, so an
        // audit failure must never change the apparent result of that operation.
        // Guaranteed audit delivery would require an outbox/broker design.
        try {
            log.info("audit_event operation={} cardId={} transactionId={} amount={} outcome={} declineReason={}",
                    event.operation(),
                    event.cardId(),
                    event.transactionId(),
                    event.amount() == null ? null : event.amount().toPlainString(),
                    event.outcome(),
                    event.declineReason()
            );
        } catch (RuntimeException e) {
            log.warn("audit_event delivery failed operation={} cardId={}", event.operation(), event.cardId());
        }
    }
}
