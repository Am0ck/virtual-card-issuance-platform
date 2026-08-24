package com.andre.virtualcard.support;

import com.andre.virtualcard.common.audit.CardOperationAuditEvent;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * TEST-ONLY probe mirroring the production audit listener's exact
 * {@code @Async("auditExecutor") + AFTER_COMMIT} wiring so async-audit tests can assert
 * delivery deterministically (thread name, MDC propagation) using a semaphore signal.
 *
 * Registered per-test via {@code @Import} — deliberately NOT component-scanned, so it
 * neither consumes audit-executor capacity nor retains events during unrelated tests.
 */
public class AuditEventProbe {

    public record AuditRecord(CardOperationAuditEvent event, String threadName, String requestId) {
    }

    private final List<AuditRecord> records = new CopyOnWriteArrayList<>();
    // semaphore (not a latch): reusable "any delivery" signal released once per record
    private final Semaphore deliverySignal = new Semaphore(0);

    // used only by the blocked-listener independence test
    private volatile boolean gateEnabled = false;
    private volatile CardOperationAuditEvent gatedEvent;
    private final CountDownLatch enteredGate = new CountDownLatch(1);
    private final CountDownLatch releaseGate = new CountDownLatch(1);

    @Async("auditExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(CardOperationAuditEvent event) {
        if (gateEnabled) {
            gatedEvent = event;
            enteredGate.countDown();
            try {
                releaseGate.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        records.add(new AuditRecord(event, Thread.currentThread().getName(), MDC.get("requestId")));
        deliverySignal.release();
    }

    /** Blocks (bounded) until at least one further event is dispatched and recorded. */
    public boolean awaitAnyDelivery(long timeoutSeconds) throws InterruptedException {
        return deliverySignal.tryAcquire(timeoutSeconds, TimeUnit.SECONDS);
    }

    public void enableGate() {
        gateEnabled = true;
    }

    public void disableGateAndRelease() {
        gateEnabled = false;
        releaseGate.countDown();
    }

    public boolean awaitEnteredGate(int timeoutSeconds) throws InterruptedException {
        return enteredGate.await(timeoutSeconds, TimeUnit.SECONDS);
    }

    /** The event that entered the gate; visible after {@link #awaitEnteredGate}. */
    public CardOperationAuditEvent getGatedEvent() {
        return gatedEvent;
    }

    public List<AuditRecord> getRecords() {
        return records;
    }
}
