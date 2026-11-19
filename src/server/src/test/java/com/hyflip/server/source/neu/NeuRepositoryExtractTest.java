package com.hyflip.server.source.neu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NeuRepositoryExtractTest {

    @TempDir
    Path tmp;

    @Test
    void extractsOnlyItemsAndConstants() throws Exception {
        Path zip = zip("REPO-abc/items/A.json", "REPO-abc/constants/c.json", "REPO-abc/README.md",
                "REPO-abc/mobs/m.json");
        Path out = tmp.resolve("out");
        NeuRepository.extract(zip, out);
        assertThat(out.resolve("items/A.json")).exists();
        assertThat(out.resolve("constants/c.json")).exists();
        assertThat(out.resolve("README.md")).doesNotExist();
        assertThat(out.resolve("mobs")).doesNotExist();
    }

    @Test
    void rejectsEntriesEscapingTheTarget() throws Exception {
        Path zip = zip("REPO-abc/items/../../../evil.json");
        assertThatThrownBy(() -> NeuRepository.extract(zip, tmp.resolve("out"))).isInstanceOf(IOException.class);
        assertThat(tmp.resolve("evil.json")).doesNotExist();
    }

    private Path zip(String... names) throws IOException {
        Path file = tmp.resolve("repo.zip");
        try (OutputStream os = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(os)) {
            for (String name : names) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write("{}".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }
}
