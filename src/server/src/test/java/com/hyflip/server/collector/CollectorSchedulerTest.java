package com.hyflip.server.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hyflip.server.config.HyFlipProperties;
import com.hyflip.server.source.hypixel.HypixelApiException;
import com.hyflip.server.storage.CollectorRunRepository;
import com.hyflip.server.storage.GapRepository;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.scheduling.TaskScheduler;

class CollectorSchedulerTest {

    private static final Collector MINUTE = new Collector() {
        public String name() {
            return "T";
        }

        public Duration interval() {
            return Duration.ofMinutes(1);
        }

        public int collect() {
            return 0;
        }
    };

    @Test
    void retryableErrorsBackOffExponentiallyUpToCap() {
        Exception e = new HypixelApiException(503, "down");
        assertThat(CollectorScheduler.backoff(MINUTE, e, 1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(CollectorScheduler.backoff(MINUTE, e, 3)).isEqualTo(Duration.ofMinutes(4));
        assertThat(CollectorScheduler.backoff(MINUTE, e, 20)).isEqualTo(CollectorScheduler.MAX_BACKOFF);
    }

    @Test
    void aRunCutOffByShutdownIsNotAFailure() {
        CollectorRunRepository runs = mock(CollectorRunRepository.class);
        // The pool is closed under the run, so recording its end fails too.
        doThrow(new CannotGetJdbcConnectionException("closed", new SQLException("pool closed")))
                .when(runs).finish(anyLong(), anyLong(), anyString(), any(), any());
        AtomicReference<CollectorScheduler> scheduler = new AtomicReference<>();
        Collector cutOff = new Collector() {
            public String name() {
                return "T";
            }

            public Duration interval() {
                return Duration.ofMinutes(1);
            }

            public int collect() {
                scheduler.get().onShutdown();
                throw new IllegalStateException("HikariDataSource has been closed");
            }
        };
        scheduler.set(new CollectorScheduler(List.of(cutOff), mock(TaskScheduler.class), runs,
                mock(GapRepository.class), mock(HyFlipProperties.class)));

        assertThat(scheduler.get().runOnce(cutOff)).isTrue();
        assertThat(scheduler.get().states().get("T").consecutiveFailures()).isZero();
        assertThat(scheduler.get().runOnce(cutOff)).as("no new runs while stopping").isFalse();
        verify(runs, never()).finish(anyLong(), anyLong(), anyString(), any(), any());
    }

    @Test
    void nonRetryableErrorsKeepTheNormalSchedule() {
        assertThat(CollectorScheduler.backoff(MINUTE, new HypixelApiException(403, "key"), 5)).isZero();
    }
}
