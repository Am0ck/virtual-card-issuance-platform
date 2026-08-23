package com.andre.virtualcard.idempotency;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdempotencyKeyHasherTest {

    @Test
    void sameKeyProducesSameDeterministicHash() {
        assertEquals(
                IdempotencyKeyHasher.hash("7c7edbec-4681-4768-9b42-bf6bfd19d3cf"),
                IdempotencyKeyHasher.hash("7c7edbec-4681-4768-9b42-bf6bfd19d3cf"));
    }

    @Test
    void differentKeysProduceDifferentHashes() {
        assertNotEquals(
                IdempotencyKeyHasher.hash("key-one"),
                IdempotencyKeyHasher.hash("key-two"));
    }

    @Test
    void hashDoesNotContainTheRawKeyAndIsBounded() {
        String raw = "my-very-secret-client-key-value";
        String hash = IdempotencyKeyHasher.hash(raw);

        assertEquals(12, hash.length());
        assertTrue(hash.matches("[0-9a-f]{12}"));
        assertTrue(!hash.contains(raw));
        // deterministic across calls
        assertEquals(hash, IdempotencyKeyHasher.hash(raw));
    }
}
