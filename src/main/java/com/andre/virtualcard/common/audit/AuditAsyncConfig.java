package com.andre.virtualcard.common.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Async infrastructure for the best-effort AFTER_COMMIT audit bonus.
 *
 * The executor is deliberately small and bounded. Saturation DISCARDS the audit work
 * with a warning: the financial operation is already committed when audit dispatch
 * happens, so a rejected/dropped audit must never fail or delay the caller, and running
 * rejected work on the request thread would re-couple audit latency into the request.
 * Guaranteed audit delivery would require an outbox/broker design.
 */
@Configuration
@EnableAsync
public class AuditAsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AuditAsyncConfig.class);

    @Bean(name = "auditExecutor")
    public ThreadPoolTaskExecutor auditExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("audit-");
        executor.setRejectedExecutionHandler(new DiscardWithWarningPolicy());
        executor.setTaskDecorator(new MdcPropagatingTaskDecorator());
        // graceful shutdown: let in-flight audit work finish, bounded wait
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }

    /**
     * Drops rejected audit work with a bounded warning. Deliberately NOT CallerRuns:
     * that would execute audit logging on the request thread after commit.
     */
    private static final class DiscardWithWarningPolicy implements java.util.concurrent.RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable runnable, ThreadPoolExecutor poolExecutor) {
            log.warn("audit_event dropped: audit executor saturated");
        }
    }

    /**
     * Copies the caller's MDC (requestId correlation) onto the audit worker and clears
     * it afterwards to prevent cross-task leakage.
     */
    private static final class MdcPropagatingTaskDecorator implements TaskDecorator {
        @Override
        public Runnable decorate(Runnable delegate) {
            Map<String, String> context = MDC.getCopyOfContextMap();
            return () -> {
                if (context != null) {
                    MDC.setContextMap(context);
                }
                try {
                    delegate.run();
                } finally {
                    MDC.clear();
                }
            };
        }
    }
}
