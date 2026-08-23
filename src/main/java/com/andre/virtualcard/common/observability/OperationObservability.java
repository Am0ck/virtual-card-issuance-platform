package com.andre.virtualcard.common.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Central low-cardinality financial-operation metrics.
 * Never tag with cardId/transactionId/requestId/idempotency key/name — those are
 * log-correlation concerns, not metric labels.
 */
@Component
public class OperationObservability {

    private static final String OPERATIONS_METRIC = "virtual_card.operations";
    private static final String DURATION_METRIC = "virtual_card.operation.duration";

    public static final String OUTCOME_SUCCESSFUL = "SUCCESSFUL";
    public static final String OUTCOME_DECLINED = "DECLINED";
    public static final String OUTCOME_CONFLICT = "CONFLICT";
    public static final String OUTCOME_NOT_FOUND = "NOT_FOUND";
    public static final String OUTCOME_INVALID = "INVALID";
    public static final String OUTCOME_ERROR = "ERROR";

    public static final String REASON_NONE = "NONE";

    private final MeterRegistry meterRegistry;

    public OperationObservability(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordOperation(String operation, String outcome, String reasonTag, boolean replay, long durationMs) {
        Counter.builder(OPERATIONS_METRIC)
                .tag("operation", operation)
                .tag("outcome", outcome)
                .tag("reason", reasonTag)
                .tag("replay", Boolean.toString(replay))
                .register(meterRegistry)
                .increment();
        Timer.builder(DURATION_METRIC)
                .tag("operation", operation)
                .register(meterRegistry)
                .record(Duration.ofMillis(durationMs));
    }
}
