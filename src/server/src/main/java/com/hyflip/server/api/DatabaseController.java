package com.hyflip.server.api;

import com.hyflip.server.storage.DatabaseTransfer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Market history export and import. */
@RestController
@RequestMapping("/api/v1/database")
public class DatabaseController {

    private final DatabaseTransfer transfer;
    private final com.hyflip.server.market.MarketService markets;

    public DatabaseController(DatabaseTransfer transfer, com.hyflip.server.market.MarketService markets) {
        this.transfer = transfer;
        this.markets = markets;
    }

    /** Imported snapshots can fall in hours whose averages are kept: rebuild them. */
    private DatabaseTransfer.ImportResult imported(DatabaseTransfer.ImportResult result) {
        markets.invalidateAverages();
        return result;
    }

    /** @param from start of the range (epoch ms), null for all; @param to end of the range */
    public record ExportRequest(Long from, Long to) {
    }

    @PostMapping("/export")
    public DatabaseTransfer.ExportResult export(@RequestBody(required = false) ExportRequest request) {
        return request == null ? transfer.export(null, null) : transfer.export(request.from(), request.to());
    }

    public record ExportFile(String name, long bytes, long modifiedAt) {
    }

    @GetMapping("/exports")
    public List<ExportFile> exports() {
        return transfer.exports().stream().map(p -> {
            try {
                return new ExportFile(p.getFileName().toString(), Files.size(p),
                        Files.getLastModifiedTime(p).toMillis());
            } catch (IOException e) {
                return new ExportFile(p.getFileName().toString(), 0, 0);
            }
        }).toList();
    }

    @GetMapping("/exports/{name}")
    public ResponseEntity<Resource> download(@PathVariable String name) {
        return transfer.exportFile(name)
                .<ResponseEntity<Resource>>map(p -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build()
                                .toString())
                        .body(new FileSystemResource(p)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** @param path a database file on this machine */
    public record ImportRequest(String path) {
    }

    @PostMapping(value = "/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DatabaseTransfer.ImportResult importPath(@RequestBody ImportRequest request) {
        if (request.path() == null || request.path().isBlank()) {
            throw new DatabaseTransfer.TransferException("path is required", false);
        }
        return imported(transfer.importFile(Path.of(request.path())));
    }

    /** The same, with the file uploaded (for the browser, which cannot pass a path). */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DatabaseTransfer.ImportResult importUpload(@RequestParam("file") MultipartFile file) throws IOException {
        Path dir = transfer.exportDir().resolveSibling("imports");
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "import-", ".db");
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            return imported(transfer.importFile(tmp));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
