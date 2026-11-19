package com.hyflip.server.api;

import com.hyflip.server.icons.PackIcons;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Item icons from the Hypixel SkyBlock resource pack on this computer. */
@RestController
@RequestMapping("/api/v1/icons")
public class IconController {

    private final PackIcons icons;

    public IconController(PackIcons icons) {
        this.icons = icons;
    }

    /** The icon index, or 204 when no pack is loaded. */
    @GetMapping
    public ResponseEntity<PackIcons.IconIndex> index() {
        return icons.index().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** The atlas image; the UI asks for it with {@code ?v=<builtAt>}, so it can be cached for good. */
    @GetMapping("/atlas.png")
    public ResponseEntity<Resource> atlas() {
        Path atlas = icons.atlas();
        if (icons.index().isEmpty() || !Files.isRegularFile(atlas)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(365)).cachePrivate())
                .body(new FileSystemResource(atlas));
    }

    /** SkyBlock packs found in the usual Minecraft folders, newest first. */
    @GetMapping("/candidates")
    public List<PackIcons.Candidate> candidates() {
        return icons.detect();
    }

    /** @param path a resource pack zip on this computer */
    public record ImportRequest(String path) {
    }

    @PostMapping(value = "/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PackIcons.IconIndex importPath(@RequestBody ImportRequest request) {
        if (request.path() == null || request.path().isBlank()) {
            throw new PackIcons.IconException("path is required");
        }
        return icons.importFrom(Path.of(request.path().trim()));
    }

    /** The same, with the file uploaded (for the browser, which cannot pass a path). */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PackIcons.IconIndex importUpload(@RequestParam("file") MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            return icons.importUpload(in, file.getOriginalFilename());
        }
    }

    @DeleteMapping
    public ResponseEntity<Void> clear() {
        icons.clear();
        return ResponseEntity.noContent().build();
    }
}
