package com.hyflip.server.config;

import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Stops the server once the desktop shell that started it is gone. The shell stops the server when its window
 * closes, but a crash, a kill from the task manager or a logout skips that; without this the server would keep
 * collecting, and the next launch would start a second one on the same database.
 *
 * <p>The shell passes its process ID in {@code HYFLIP_PARENT_PID}; a server started by hand has none and is
 * never stopped by this.
 */
@Component
public class ParentWatch {

    public static final String ENV = "HYFLIP_PARENT_PID";
    private static final Logger log = LoggerFactory.getLogger(ParentWatch.class);

    private final OptionalLong parent;
    private final Runnable onGone;
    private volatile boolean stopping;

    @Autowired
    public ParentWatch(Environment env, ConfigurableApplicationContext context) {
        this(env.getProperty(ENV), () -> {
            // Off the scheduler thread: closing the context waits for scheduled tasks, this one included.
            Thread t = new Thread(() -> System.exit(SpringApplication.exit(context, () -> 0)), "parent-gone");
            t.setDaemon(false);
            t.start();
        });
    }

    ParentWatch(String pid, Runnable onGone) {
        this.parent = parse(pid);
        this.onGone = onGone;
        parent.ifPresent(p -> log.info("Stopping when the desktop app (process {}) exits", p));
    }

    static OptionalLong parse(String pid) {
        if (pid == null || pid.isBlank()) {
            return OptionalLong.empty();
        }
        try {
            long p = Long.parseLong(pid.trim());
            return p > 0 ? OptionalLong.of(p) : OptionalLong.empty();
        } catch (NumberFormatException e) {
            log.warn("Ignoring {}={}: not a process ID", ENV, pid);
            return OptionalLong.empty();
        }
    }

    @Scheduled(initialDelay = 2_000, fixedDelay = 2_000)
    public void check() {
        if (stopping || parent.isEmpty()) {
            return;
        }
        long p = parent.getAsLong();
        if (!ProcessHandle.of(p).map(ProcessHandle::isAlive).orElse(false)) {
            stopping = true;
            log.warn("The desktop app (process {}) is gone; stopping the server", p);
            onGone.run();
        }
    }
}
