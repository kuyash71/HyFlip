package com.hyflip.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ParentWatchTest {

    @Test
    void stopsOnceWhenTheParentIsGone() throws Exception {
        Process child = new ProcessBuilder("java", "-version").redirectErrorStream(true).start();
        child.getInputStream().readAllBytes();
        child.waitFor();
        AtomicInteger stops = new AtomicInteger();
        ParentWatch watch = new ParentWatch(String.valueOf(child.pid()), stops::incrementAndGet);
        watch.check();
        watch.check();
        assertThat(stops).hasValue(1);
    }

    @Test
    void keepsRunningWhileTheParentLives() {
        AtomicInteger stops = new AtomicInteger();
        new ParentWatch(String.valueOf(ProcessHandle.current().pid()), stops::incrementAndGet).check();
        assertThat(stops).hasValue(0);
    }

    @Test
    void aServerStartedByHandIsNeverStopped() {
        AtomicInteger stops = new AtomicInteger();
        for (String pid : new String[] {null, "", "abc", "-4"}) {
            new ParentWatch(pid, stops::incrementAndGet).check();
        }
        assertThat(stops).hasValue(0);
    }
}
