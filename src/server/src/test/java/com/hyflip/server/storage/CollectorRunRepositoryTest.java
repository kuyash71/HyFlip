package com.hyflip.server.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyflip.server.support.DatabaseTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class CollectorRunRepositoryTest extends DatabaseTest {

    @Autowired CollectorRunRepository runs;
    @Autowired JdbcClient jdbc;

    @Test
    void concurrentStartsGetTheirOwnIds() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Long>> tasks = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                String name = "CONCURRENT_" + (i % 4);
                tasks.add(() -> {
                    long id = runs.start(name, 1);
                    runs.finish(id, 2, "OK", 0, null);
                    return id;
                });
            }
            List<Long> ids = new ArrayList<>();
            for (Future<Long> f : pool.invokeAll(tasks)) {
                ids.add(f.get());
            }
            assertThat(ids).doesNotHaveDuplicates();
        } finally {
            pool.shutdown();
        }
        long running = jdbc.sql("SELECT count(*) FROM collector_run WHERE collector LIKE 'CONCURRENT_%' AND status <> 'OK'")
                .query(Long.class).single();
        assertThat(running).isZero();
    }

    @Test
    void latestHandlesRunsThatAreStillRunning() {
        runs.start("STILL_RUNNING", 5);
        CollectorRunRepository.Run run = runs.latest().stream()
                .filter(r -> r.collector().equals("STILL_RUNNING")).findFirst().orElseThrow();
        assertThat(run.finishedAt()).isNull();
        assertThat(run.rowsWritten()).isNull();
        assertThat(run.status()).isEqualTo("RUNNING");
    }
}
