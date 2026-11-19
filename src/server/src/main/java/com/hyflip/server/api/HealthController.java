package com.hyflip.server.api;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness check used by the desktop app to know when the server is ready. */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    private final String version;

    public HealthController(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.getIfAvailable();
        this.version = build != null ? build.getVersion() : "dev";
    }

    @GetMapping
    public Health health() {
        return new Health("UP", version);
    }

    public record Health(String status, String version) {
    }
}
