package com.hyflip.server.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Test fixtures trimmed from live Hypixel and NEU data (2026-11-17). */
public final class Fixtures {

    public static final ObjectMapper JSON = JsonMapper.builder().build();

    private Fixtures() {
    }

    public static <T> T json(String path, Class<T> type) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + path)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + path);
            }
            return JSON.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The trimmed NEU repository ({@code items/}, {@code constants/}). */
    public static Path neuRepo() {
        try {
            return Path.of(Fixtures.class.getResource("/fixtures/neu").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
