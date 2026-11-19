package com.hyflip.server.source.neu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class NeuRepositoryRetryTest {

    @Test
    void retriesWhileAFileIsBrieflyLocked() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        NeuRepository.withRetry(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new AccessDeniedException("repo");
            }
        });
        assertThat(calls).hasValue(3);
    }

    @Test
    void givesUpAfterFiveTries() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> NeuRepository.withRetry(() -> {
            calls.incrementAndGet();
            throw new AccessDeniedException("repo");
        })).isInstanceOf(AccessDeniedException.class);
        assertThat(calls).hasValue(5);
    }

    @Test
    void doesNotRetryAMissingFile() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> NeuRepository.withRetry(() -> {
            calls.incrementAndGet();
            throw new NoSuchFileException("repo");
        })).isInstanceOf(NoSuchFileException.class);
        assertThat(calls).hasValue(1);
    }
}
