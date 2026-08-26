package com.andre.virtualcard.common.ratelimit;

import com.andre.virtualcard.common.error.ApiErrorCode;
import com.andre.virtualcard.common.error.ProblemDetailFactory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final String HEALTH_PATH = "/actuator/health";

    private final RateLimitService rateLimitService;
    private final RateLimitProperties properties;
    private final ProblemDetailFactory problemDetailFactory;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public RateLimitFilter(RateLimitService rateLimitService,
                           RateLimitProperties properties,
                           ProblemDetailFactory problemDetailFactory,
                           ObjectMapper objectMapper,
                           MeterRegistry meterRegistry) {
        this.rateLimitService = rateLimitService;
        this.properties = properties;
        this.problemDetailFactory = problemDetailFactory;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!properties.enabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        RateLimitPolicy policy = resolvePolicy(request);
        String clientId = request.getRemoteAddr();

        RateLimitDecision decision = rateLimitService.tryConsume(clientId, policy);

        if (decision.isAllowed()) {
            filterChain.doFilter(request, response);
            return;
        }

        log.debug("rate_limit_rejected policy={} retryAfterSeconds={}", policy, decision.retryAfterSeconds());

        Counter.builder("virtual_card.rate_limit.rejected")
                .tag("policy", policy.name().toLowerCase())
                .register(meterRegistry)
                .increment();

        ProblemDetail problem = problemDetailFactory.create(
                HttpStatus.TOO_MANY_REQUESTS,
                ApiErrorCode.RATE_LIMIT_EXCEEDED,
                "Request rate limit exceeded. Retry later.",
                request);

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
        response.setContentType("application/problem+json");
        response.getOutputStream().write(objectMapper.writeValueAsBytes(problem));
        response.getOutputStream().flush();
    }

    private RateLimitPolicy resolvePolicy(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (HEALTH_PATH.equals(path)) {
            return RateLimitPolicy.HEALTH;
        }
        return RateLimitPolicy.API;
    }
}
