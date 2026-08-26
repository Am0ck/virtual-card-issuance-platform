package com.andre.virtualcard.common.ratelimit;

import com.andre.virtualcard.card.CardRepository;
import com.andre.virtualcard.idempotency.IdempotencyRepository;
import com.andre.virtualcard.support.AbstractPostgreSQLIntegrationTest;
import com.andre.virtualcard.transaction.CardTransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rate limiter integration tests with deliberately tiny API quota (capacity 2,
 * no refill during the test hour) so exhaustion is deterministic. Health quota
 * is generous to prove policy isolation. Each test uses a unique deterministic
 * remote address to avoid bucket-cache contamination across test methods.
 */
@SpringBootTest(properties = {
        "rate-limit.api.capacity=2",
        "rate-limit.api.refill-tokens=2",
        "rate-limit.api.refill-period=PT1H",
        "rate-limit.health.capacity=100",
        "rate-limit.health.refill-tokens=100",
        "rate-limit.health.refill-period=PT1H",
        "card.expiration.cleanup-interval-ms=9223372036854775807"
})
class RateLimitIntegrationTest extends AbstractPostgreSQLIntegrationTest {

    private static final AtomicInteger COUNTER = new AtomicInteger(0);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private CardTransactionRepository cardTransactionRepository;

    @Autowired
    private IdempotencyRepository idempotencyRepository;

    private static String nextAddr() {
        int seq = COUNTER.incrementAndGet();
        return "10.0." + (seq / 256) + "." + (seq % 256);
    }

    // ---- Test A: throttling ----

    @Test
    void firstTwoRequestsPassThroughMvcThirdIsRejected() throws Exception {
        String addr = nextAddr();

        mockMvc.perform(get("/api/v1/cards/" + UUID.randomUUID()).remoteAddress(addr))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/cards/" + UUID.randomUUID()).remoteAddress(addr))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/cards/" + UUID.randomUUID()).remoteAddress(addr))
                .andExpect(status().isTooManyRequests());
    }

    // ---- Test B: error format ----

    @Test
    void rejectedResponseFollowsProblemDetailContract() throws Exception {
        String addr = nextAddr();

        mockMvc.perform(get("/api/v1/cards/" + UUID.randomUUID()).remoteAddress(addr));
        mockMvc.perform(get("/api/v1/cards/" + UUID.randomUUID()).remoteAddress(addr));

        mockMvc.perform(get("/api/v1/cards/" + UUID.randomUUID()).remoteAddress(addr))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.containsString("application/problem+json")))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.title").value("Rate limit exceeded"))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    // ---- Test C: request ID consistency ----

    @Test
    void xRequestIdHeaderMatchesProblemDetailRequestId() throws Exception {
        String addr = nextAddr();

        mockMvc.perform(get("/api/v1/cards/" + UUID.randomUUID()).remoteAddress(addr));
        mockMvc.perform(get("/api/v1/cards/" + UUID.randomUUID()).remoteAddress(addr));

        var result = mockMvc.perform(get("/api/v1/cards/" + UUID.randomUUID()).remoteAddress(addr))
                .andExpect(status().isTooManyRequests())
                .andReturn();

        String headerValue = result.getResponse().getHeader("X-Request-Id");
        String bodyRequestId = result.getResponse().getContentAsString()
                .replaceAll(".*\"requestId\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        assertThat(headerValue).isNotBlank();
        assertThat(UUID.fromString(headerValue)).isNotNull();
        assertThat(bodyRequestId).isEqualTo(headerValue);
    }

    // ---- Test D: client isolation ----

    @Test
    void clientAExhaustionDoesNotAffectClientB() throws Exception {
        String addrA = nextAddr();
        String addrB = nextAddr();

        mockMvc.perform(get("/api/v1/cards/" + UUID.randomUUID()).remoteAddress(addrA));
        mockMvc.perform(get("/api/v1/cards/" + UUID.randomUUID()).remoteAddress(addrA));
        mockMvc.perform(get("/api/v1/cards/" + UUID.randomUUID()).remoteAddress(addrA))
                .andExpect(status().isTooManyRequests());

        mockMvc.perform(get("/api/v1/cards/" + UUID.randomUUID()).remoteAddress(addrB))
                .andExpect(status().isNotFound());
    }

    // ---- Test E: health isolation ----

    @Test
    void exhaustedApiQuotaDoesNotAffectHealthEndpoint() throws Exception {
        String addr = nextAddr();

        mockMvc.perform(get("/api/v1/cards/" + UUID.randomUUID()).remoteAddress(addr));
        mockMvc.perform(get("/api/v1/cards/" + UUID.randomUUID()).remoteAddress(addr));
        mockMvc.perform(get("/api/v1/cards/" + UUID.randomUUID()).remoteAddress(addr))
                .andExpect(status().isTooManyRequests());

        mockMvc.perform(get("/actuator/health").remoteAddress(addr))
                .andExpect(status().isOk());
    }

    // ---- Test F: rejected mutation performs zero DB writes ----

    @Test
    void rejectedRequestCreatesNoCardNoTransactionNoIdempotencyRow() throws Exception {
        String addr = nextAddr();
        long cardsBefore = cardRepository.count();
        long txnsBefore = cardTransactionRepository.count();
        long idempBefore = idempotencyRepository.count();

        mockMvc.perform(get("/api/v1/cards/" + UUID.randomUUID()).remoteAddress(addr));
        mockMvc.perform(get("/api/v1/cards/" + UUID.randomUUID()).remoteAddress(addr));

        mockMvc.perform(post("/api/v1/cards")
                        .remoteAddress(addr)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardholderName\": \"Should Not Exist\", \"initialBalance\": 50.00}"))
                .andExpect(status().isTooManyRequests());

        assertThat(cardRepository.count()).isEqualTo(cardsBefore);
        assertThat(cardTransactionRepository.count()).isEqualTo(txnsBefore);
        assertThat(idempotencyRepository.count()).isEqualTo(idempBefore);
    }
}
