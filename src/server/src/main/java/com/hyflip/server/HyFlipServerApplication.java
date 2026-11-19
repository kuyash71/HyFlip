package com.hyflip.server;

import com.hyflip.server.config.LegacyNames;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point of the HyFlip local server.
 *
 * <p>The server is started by the desktop app as a child process and listens on 127.0.0.1 only.
 * It reads external sources, stores snapshots in SQLite and serves the UI over REST. It never
 * sends anything to the game.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class HyFlipServerApplication {

    public static void main(String[] args) {
        LegacyNames.migrate(args);
        SpringApplication.run(HyFlipServerApplication.class, args);
    }
}
