package com.andre.virtualcard.common.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Best-effort AFTER_COMMIT audit: runs asynchronously on the bounded auditExecutor.
 *
 * - rollback, technical failure, and missing-card cases produce no audit (no commit,
 *   no AFTER_COMMIT dispatch)
 * - idempotency replays publish nothing because they perform no business mutation
 * - audit failures are isolated from the already-committed financial operation
 *
 * Guaranteed external delivery would require an outbox/broker design.
 */
@Component
public class CardOperationAuditListener {

    private static final Logger log = LoggerFactory.getLogger(CardOperationAuditListener.class);

    @Async("auditExecutor")
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
