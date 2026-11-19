package com.hyflip.server.source.neu;

import com.hyflip.server.config.HyFlipProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Local copy of the NEU repository (MIT licensed) under {@code <data-dir>/neu}.
 *
 * <p>{@link #sync()} asks GitHub for the latest commit and downloads the archive (about 22 MB) only
 * when it changed. Only {@code items/} and {@code constants/} are extracted.
 */
@Service
public class NeuRepository {

    private static final Logger log = LoggerFactory.getLogger(NeuRepository.class);
    private static final long MAX_ENTRY_BYTES = 20L * 1024 * 1024;

    private final RestClient http;
    private final NeuParser parser;
    private final HyFlipProperties.Neu config;
    private final Path root;
    private final AtomicReference<NeuData> current = new AtomicReference<>();

    public NeuRepository(RestClient externalRestClient, ObjectMapper json, HyFlipProperties properties) {
        this.http = externalRestClient;
        this.parser = new NeuParser(json);
        this.config = properties.neu();
        this.root = properties.dataDir().resolve("neu");
    }

    public Optional<NeuData> current() {
        return Optional.ofNullable(current.get());
    }

    /** Loads the copy already on disk, if any. Returns true if data is now available. */
    public boolean loadLocal() throws IOException {
        Path repo = root.resolve("repo");
        Path commitFile = root.resolve("COMMIT");
        if (!Files.isDirectory(repo) || !Files.exists(commitFile)) {
            return false;
        }
        current.set(parser.parse(repo, Files.readString(commitFile).trim()));
        log.info("Loaded NEU repository {} from disk", current.get().commit());
        return true;
    }

    /** Downloads the latest commit if it differs from the loaded one. Returns true if it changed. */
    public boolean sync() throws IOException {
        String latest = latestCommit();
        NeuData loaded = current.get();
        if (loaded != null && loaded.commit().equals(latest)) {
            return false;
        }
        Files.createDirectories(root);
        deleteLeftovers();
        // Unique names, so a second server process on the same data dir cannot delete our files.
        Path archive = Files.createTempFile(root, "download-", ".zip");
        Path staging = Files.createTempDirectory(root, "staging-");
        try {
            download(config.archiveUrl().replace("{sha}", latest), archive);
            extract(archive, staging);
            NeuData parsed = parser.parse(staging, latest);
            Path repo = root.resolve("repo");
            // Move the old copy aside instead of deleting it in place: on Windows a file held open by an antivirus
            // scan or the indexer cannot be deleted, and a half-deleted repo would break the next start. The old
            // copy's name starts with "staging", so deleteLeftovers() removes it later if it cannot go now.
            Path old = root.resolve("staging-old-" + System.nanoTime());
            if (Files.exists(repo)) {
                withRetry(() -> Files.move(repo, old, StandardCopyOption.ATOMIC_MOVE));
            }
            withRetry(() -> Files.move(staging, repo, StandardCopyOption.ATOMIC_MOVE));
            try {
                deleteRecursively(old);
            } catch (IOException e) {
                log.info("Could not remove the previous NEU copy yet ({}); it is removed on a later sync", e.toString());
            }
            Files.writeString(root.resolve("COMMIT"), latest);
            current.set(parsed);
            log.info("Updated NEU repository to {}: {} items, {} recipes", latest, parsed.items().size(),
                    parsed.recipes().size());
            return true;
        } finally {
            Files.deleteIfExists(archive);
            deleteRecursively(staging);
        }
    }

    private String latestCommit() {
        JsonNode body = http.get().uri(config.commitUrl())
                .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .retrieve().body(JsonNode.class);
        JsonNode sha = body == null ? null : body.get("sha");
        if (sha == null || !sha.asString().matches("[0-9a-f]{40}")) {
            throw new IllegalStateException("GitHub returned no commit SHA");
        }
        return sha.asString();
    }

    private void download(String url, Path target) {
        http.get().uri(url).exchange((request, response) -> {
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IOException("NEU archive download returned " + response.getStatusCode());
            }
            try (InputStream body = response.getBody()) {
                Files.copy(body, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return null;
        });
    }

    /** Extracts {@code <top>/items/*} and {@code <top>/constants/*} into {@code target}. */
    static void extract(Path archive, Path target) throws IOException {
        Files.createDirectories(target);
        Path base = target.toAbsolutePath().normalize();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                int slash = name.indexOf('/');
                if (entry.isDirectory() || slash < 0) {
                    continue;
                }
                String relative = name.substring(slash + 1);
                if (!relative.startsWith("items/") && !relative.startsWith("constants/")) {
                    continue;
                }
                Path out = base.resolve(relative).normalize();
                if (!out.startsWith(base)) {
                    throw new IOException("Zip entry escapes target: " + name);
                }
                Files.createDirectories(out.getParent());
                copyEntry(zip, out, name);
                zip.closeEntry();
            }
        }
    }

    /** Copies the current zip entry without closing the zip stream, refusing oversized entries. */
    private static void copyEntry(InputStream zip, Path out, String name) throws IOException {
        try (OutputStream file = Files.newOutputStream(out)) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int n;
            while ((n = zip.read(buffer)) > 0) {
                total += n;
                if (total > MAX_ENTRY_BYTES) {
                    throw new IOException("Zip entry too large: " + name);
                }
                file.write(buffer, 0, n);
            }
        }
    }

    /** Removes temporary files of syncs that were killed (older than an hour, so never a running one). */
    private void deleteLeftovers() throws IOException {
        long cutoff = System.currentTimeMillis() - 3_600_000;
        try (Stream<Path> files = Files.list(root)) {
            for (Path p : files.toList()) {
                String name = p.getFileName().toString();
                boolean temporary = name.startsWith("download") || name.startsWith("staging");
                if (temporary && Files.getLastModifiedTime(p).toMillis() < cutoff) {
                    deleteRecursively(p);
                }
            }
        }
    }

    interface FileOp {
        void run() throws IOException;
    }

    /** Retries a rename that Windows refuses while another process briefly holds a file open. */
    static void withRetry(FileOp op) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                op.run();
                return;
            } catch (FileSystemException e) {
                if (attempt >= 5 || e instanceof NoSuchFileException || e instanceof FileAlreadyExistsException) {
                    throw e;
                }
                try {
                    Thread.sleep(200L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
