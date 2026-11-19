package com.hyflip.server.icons;

import com.hyflip.server.collector.NeuUpdatedEvent;
import com.hyflip.server.config.HyFlipProperties;
import com.hyflip.server.source.hypixel.HypixelApi;
import com.hyflip.server.source.hypixel.ItemsResponse;
import com.hyflip.server.source.neu.NeuRepository;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Builds item icons from the Hypixel SkyBlock resource pack the player points to, and keeps them current.
 *
 * <p>The icons go into one atlas ({@code <data-dir>/icons/atlas.png}, {@value #CELL} px cells) and an index of which
 * cell each item ID uses ({@code index.json}). Item IDs come from the {@code item_model} field of Hypixel's items
 * API; pack items it does not list are matched by file name to known item IDs. A copy of the pack is kept next to
 * the atlas, so the atlas can be rebuilt after the source is gone.
 *
 * <p>The pack the game downloads from Hypixel is a new file whenever Hypixel changes it. Once icons are loaded,
 * {@link #refresh()} looks for a newer pack in the usual Minecraft folders and rebuilds from it.
 */
@Service
public class PackIcons {

    /** Atlas cell size. Textures are scaled by whole steps only, so pixel art keeps square pixels. */
    public static final int CELL = 32;
    static final int VERSION = 1;
    private static final Logger log = LoggerFactory.getLogger(PackIcons.class);
    /** Folders that hold resource packs, looked for below each Minecraft root. */
    private static final Set<String> PACK_FOLDERS = Set.of("downloads", "resourcepacks", "server-resource-packs");
    private static final long MIN_PACK_BYTES = 1 << 10;
    private static final long MAX_PACK_BYTES = 1L << 30;

    private final Path dir;
    private final ObjectMapper json;
    private final Supplier<Map<String, Look>> hypixelItems;
    private final Supplier<Map<String, String>> neuMaterials;
    private final Supplier<List<Path>> roots;
    private final Supplier<Optional<Path>> client;
    private final AtomicReference<IconIndex> index = new AtomicReference<>();

    @Autowired
    public PackIcons(HyFlipProperties properties, ObjectMapper json, HypixelApi hypixel, NeuRepository neu) {
        this(properties.dataDir().resolve("icons"), json, () -> looks(hypixel), () -> neuMaterials(neu),
                PackIcons::minecraftRoots, PackIcons::findClientJar);
    }

    /**
     * @param hypixelItems item ID → how Hypixel draws it (items API)
     * @param neuMaterials item ID → NEU's 1.8 material, for items the items API does not list (enchanted books)
     * @param roots        Minecraft folders to look for packs in
     * @param client       the player's Minecraft client jar, for vanilla textures
     */
    PackIcons(Path dir, ObjectMapper json, Supplier<Map<String, Look>> hypixelItems,
              Supplier<Map<String, String>> neuMaterials, Supplier<List<Path>> roots, Supplier<Optional<Path>> client) {
        this.dir = dir;
        this.json = json;
        this.hypixelItems = hypixelItems;
        this.neuMaterials = neuMaterials;
        this.roots = roots;
        this.client = client;
        loadIndex();
    }

    /** How Hypixel draws an item: its pack model, else its 1.8 vanilla material. */
    record Look(String itemModel, String material, Integer durability, boolean glowing) {
    }

    /**
     * What is loaded.
     *
     * @param cell      atlas cell size in px
     * @param columns   cells per atlas row
     * @param items     item ID → cell index
     * @param source    where the pack came from (a path, or the uploaded file's name)
     * @param sourceSize and {@code sourceModified}: the source file when it was read, to notice a newer one
     * @param pack      the pack's own description
     * @param models    item definitions in the pack
     * @param builtAt   when the atlas was built (epoch ms), also its cache key in the UI
     * @param glint     items drawn with the enchantment glint
     * @param client    the Minecraft client jar vanilla textures came from, null if none was found
     * @param fromPack  how many items got their icon from the pack (the rest are vanilla)
     */
    public record IconIndex(int version, int cell, int columns, Map<String, Integer> items, String source,
                            long sourceSize, long sourceModified, String pack, int models, long builtAt,
                            List<String> glint, String client, int fromPack) {
    }

    /** A SkyBlock pack found on this computer. */
    public record Candidate(String path, long size, long modifiedAt) {
    }

    public static class IconException extends RuntimeException {
        public IconException(String message) {
            super(message);
        }
    }

    public Optional<IconIndex> index() {
        return Optional.ofNullable(index.get());
    }

    public Path atlas() {
        return dir.resolve("atlas.png");
    }

    /** Builds icons from a pack file on this computer. */
    public synchronized IconIndex importFrom(Path source) {
        Path file = source.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            throw new IconException("No file at " + file);
        }
        if (!PackReader.isSkyBlockPack(file)) {
            throw new IconException("This is not the Hypixel SkyBlock resource pack (no hypixel_skyblock items in it)");
        }
        try {
            Files.createDirectories(dir);
            Path copy = Files.createTempFile(dir, "pack-", ".zip");
            try {
                Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
                return build(copy, file.toString(), Files.size(file), Files.getLastModifiedTime(file).toMillis());
            } finally {
                Files.deleteIfExists(copy);
            }
        } catch (IOException e) {
            throw new IconException("Could not read the pack: " + e.getMessage());
        }
    }

    /** The same, from an uploaded file (the browser cannot pass a path). */
    public synchronized IconIndex importUpload(InputStream in, String name) {
        try {
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "upload-", ".zip");
            try {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                if (!PackReader.isSkyBlockPack(tmp)) {
                    throw new IconException("This is not the Hypixel SkyBlock resource pack (no hypixel_skyblock items in it)");
                }
                return build(tmp, name == null || name.isBlank() ? "uploaded pack" : name, Files.size(tmp), 0);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new IconException("Could not read the pack: " + e.getMessage());
        }
    }

    /** Removes the icons; items show their letter again. */
    public synchronized void clear() {
        index.set(null);
        for (String name : List.of("index.json", "atlas.png", "pack.zip")) {
            try {
                Files.deleteIfExists(dir.resolve(name));
            } catch (IOException e) {
                log.warn("Could not delete {}: {}", dir.resolve(name), e.toString());
            }
        }
    }

    /** SkyBlock packs in the usual Minecraft folders, newest first. */
    public List<Candidate> detect() {
        List<Candidate> found = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        for (Path root : roots.get()) {
            for (Path folder : packFolders(root)) {
                try (Stream<Path> files = Files.walk(folder, 2)) {
                    for (Path p : files.filter(Files::isRegularFile).toList()) {
                        long size = Files.size(p);
                        if (size >= MIN_PACK_BYTES && size <= MAX_PACK_BYTES && seen.add(p.toRealPath())
                                && PackReader.isSkyBlockPack(p)) {
                            found.add(new Candidate(p.toString(), size, Files.getLastModifiedTime(p).toMillis()));
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    log.debug("Skipping {}: {}", folder, e.toString());
                }
            }
        }
        found.sort(Comparator.comparingLong(Candidate::modifiedAt).reversed());
        return found;
    }

    /**
     * Once icons are loaded, rebuilds them from a newer SkyBlock pack: a changed source file, or a newer download
     * from Hypixel. Does nothing before the player loads a pack.
     */
    @Scheduled(initialDelay = 30_000, fixedDelay = 6 * 3_600_000)
    public void refresh() {
        IconIndex current = index.get();
        if (current == null) {
            return;
        }
        try {
            if (current.version() != VERSION) {
                rebuildFromCopy(current);
                return;
            }
            Optional<Candidate> newer = Stream.concat(sourceCandidate(current).stream(), detect().stream())
                    .filter(c -> c.modifiedAt() > current.sourceModified()
                            || c.path().equals(current.source()) && c.size() != current.sourceSize())
                    .max(Comparator.comparingLong(Candidate::modifiedAt));
            if (newer.isPresent()) {
                log.info("Found a newer SkyBlock pack at {}; rebuilding the icons", newer.get().path());
                importFrom(Path.of(newer.get().path()));
            }
        } catch (RuntimeException e) {
            log.warn("Could not refresh the item icons: {}", e.getMessage());
        }
    }

    private Optional<Candidate> sourceCandidate(IconIndex current) {
        try {
            Path p = Path.of(current.source());
            return Files.isRegularFile(p) && PackReader.isSkyBlockPack(p)
                    ? Optional.of(new Candidate(p.toString(), Files.size(p), Files.getLastModifiedTime(p).toMillis()))
                    : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * NEU supplies enchanted books and the IDs pack files are matched against. On a first start the player may load
     * a pack before NEU is downloaded; rebuild from the kept copy once it is, and after each NEU update.
     */
    @EventListener(NeuUpdatedEvent.class)
    public void onNeuUpdated() {
        IconIndex current = index.get();
        if (current != null) {
            try {
                rebuildFromCopy(current);
            } catch (RuntimeException e) {
                log.warn("Could not rebuild the item icons after the NEU update: {}", e.getMessage());
            }
        }
    }

    /** A newer HyFlip builds atlases differently, or NEU changed: rebuild from the kept copy. */
    private synchronized void rebuildFromCopy(IconIndex current) {
        Path copy = dir.resolve("pack.zip");
        if (!Files.isRegularFile(copy)) {
            return;
        }
        try {
            Path tmp = Files.createTempFile(dir, "pack-", ".zip");
            try {
                Files.copy(copy, tmp, StandardCopyOption.REPLACE_EXISTING);
                build(tmp, current.source(), current.sourceSize(), current.sourceModified());
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            log.warn("Could not rebuild the item icons: {}", e.toString());
        }
    }

    /** Reads every item icon of the pack at {@code pack} and writes atlas, index and the pack copy. */
    private IconIndex build(Path pack, String source, long sourceSize, long sourceModified) throws IOException {
        long started = System.currentTimeMillis();
        Map<String, Look> looks = safe("Hypixel items", hypixelItems, Map.of());
        Map<String, String> neu = safe("NEU items", neuMaterials, Map.of());
        Optional<Path> jar = safe("Minecraft client", client, Optional.empty());
        Map<String, String> models = new TreeMap<>();
        Set<String> glint = new java.util.TreeSet<>();
        looks.forEach((id, look) -> {
            if (look.itemModel() != null && !look.itemModel().isBlank()) {
                models.put(id, look.itemModel());
            }
            if (look.glowing()) {
                glint.add(id);
            }
        });
        String description;
        int definitions;
        int fromPack;
        List<BufferedImage> cells = new ArrayList<>();
        Map<String, Integer> items = new TreeMap<>();
        List<Path> sources = new ArrayList<>(List.of(pack));
        jar.ifPresent(sources::add);
        try (PackReader reader = new PackReader(sources, json)) {
            description = reader.description();
            List<String> inPack = reader.itemModels();
            definitions = inPack.size();
            // Pack items the items API does not list: the file name is usually the item ID in lower case.
            Set<String> listed = new HashSet<>(models.values());
            for (String model : inPack) {
                String id = model.substring(model.lastIndexOf('/') + 1).toUpperCase(Locale.ROOT);
                boolean known = looks.isEmpty() && neu.isEmpty() || looks.containsKey(id) || neu.containsKey(id);
                if (!listed.contains(model) && !models.containsKey(id) && known) {
                    models.put(id, model);
                }
            }
            Set<String> packed = Set.copyOf(models.keySet());
            if (jar.isPresent()) {
                // Items Hypixel draws as a vanilla item: the client jar's texture for its 1.8 material.
                looks.forEach((id, look) -> {
                    String modern = LegacyMaterials.modern(look.material(), look.durability());
                    if (!models.containsKey(id) && !modern.isEmpty()) {
                        models.put(id, "minecraft:" + modern);
                    }
                });
                neu.forEach((id, material) -> {
                    String modern = LegacyMaterials.modern(material, null);
                    if (!models.containsKey(id) && !looks.containsKey(id) && !modern.isEmpty()) {
                        models.put(id, "minecraft:" + modern);
                        if (modern.equals("enchanted_book")) {
                            glint.add(id);
                        }
                    }
                });
            }
            Map<String, Integer> cellOfModel = new HashMap<>();
            for (Map.Entry<String, String> e : models.entrySet()) {
                Integer cell = cellOfModel.get(e.getValue());
                if (cell == null && !cellOfModel.containsKey(e.getValue())) {
                    Optional<BufferedImage> icon = reader.icon(e.getValue());
                    if (icon.isPresent()) {
                        cell = cells.size();
                        cells.add(fit(icon.get(), CELL));
                    }
                    cellOfModel.put(e.getValue(), cell);
                }
                if (cell != null) {
                    items.put(e.getKey(), cell);
                }
            }
            fromPack = (int) items.keySet().stream().filter(packed::contains).count();
        }
        glint.retainAll(items.keySet());
        if (cells.isEmpty()) {
            throw new IconException("No item icons could be read from this pack");
        }
        int columns = (int) Math.ceil(Math.sqrt(cells.size()));
        int rows = (cells.size() + columns - 1) / columns;
        BufferedImage atlas = new BufferedImage(columns * CELL, rows * CELL, BufferedImage.TYPE_INT_ARGB);
        var g = atlas.createGraphics();
        for (int i = 0; i < cells.size(); i++) {
            g.drawImage(cells.get(i), (i % columns) * CELL, (i / columns) * CELL, null);
        }
        g.dispose();

        IconIndex built = new IconIndex(VERSION, CELL, columns, Map.copyOf(items), source,
                sourceSize, sourceModified, description, definitions, System.currentTimeMillis(), List.copyOf(glint),
                jar.map(Path::toString).orElse(null), fromPack);
        Path atlasTmp = Files.createTempFile(dir, "atlas-", ".png");
        Path indexTmp = Files.createTempFile(dir, "index-", ".json");
        try {
            ImageIO.write(atlas, "png", atlasTmp.toFile());
            Files.writeString(indexTmp, json.writeValueAsString(built));
            if (!pack.equals(dir.resolve("pack.zip"))) {
                Files.copy(pack, dir.resolve("pack.zip"), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(atlasTmp, atlas(), StandardCopyOption.REPLACE_EXISTING);
            Files.move(indexTmp, dir.resolve("index.json"), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(atlasTmp);
            Files.deleteIfExists(indexTmp);
        }
        index.set(built);
        log.info("Built {} item icons ({} images) from {} in {} ms", items.size(), cells.size(), source,
                System.currentTimeMillis() - started);
        return built;
    }

    /**
     * Fits a texture into a {@code cell}-px square without blurring: smaller textures grow by a whole factor
     * (16 → 32), larger ones keep every n-th pixel (64 → 32), and odd sizes are centred.
     */
    static BufferedImage fit(BufferedImage icon, int cell) {
        int w = icon.getWidth();
        int size = w <= cell ? w * Math.max(1, cell / w) : w / (int) Math.ceil((double) w / cell);
        BufferedImage out = new BufferedImage(cell, cell, BufferedImage.TYPE_INT_ARGB);
        int offset = (cell - size) / 2;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                out.setRGB(offset + x, offset + y, icon.getRGB(x * w / size, y * w / size));
            }
        }
        return out;
    }

    private void loadIndex() {
        Path file = dir.resolve("index.json");
        if (!Files.isRegularFile(file) || !Files.isRegularFile(atlas())) {
            return;
        }
        try {
            index.set(json.readValue(Files.readString(file), IconIndex.class));
        } catch (IOException | RuntimeException e) {
            log.warn("Ignoring a broken icon index {}: {}", file, e.toString());
        }
    }

    private static <T> T safe(String what, Supplier<T> supplier, T fallback) {
        try {
            T value = supplier.get();
            return value == null ? fallback : value;
        } catch (RuntimeException e) {
            log.info("Item icons: {} unavailable ({}); matching pack files by name", what, e.toString());
            return fallback;
        }
    }

    /** How Hypixel draws each item, from its items API (public, no key needed). */
    static Map<String, Look> looks(HypixelApi hypixel) {
        Map<String, Look> looks = new HashMap<>();
        ItemsResponse items = hypixel.items();
        for (ItemsResponse.Item item : items.items()) {
            if (item.id() != null) {
                looks.put(item.id(), new Look(item.itemModel(), item.material(), item.durability(),
                        Boolean.TRUE.equals(item.glowing())));
            }
        }
        return looks;
    }

    static Map<String, String> neuMaterials(NeuRepository neu) {
        Map<String, String> materials = new HashMap<>();
        neu.current().ifPresent(d -> d.items().forEach((id, item) -> {
            if (item.material() != null) {
                materials.put(id, item.material());
            }
        }));
        return materials;
    }

    /**
     * The newest Minecraft client jar on this computer that has 1.21.4+ item definitions, from the vanilla
     * launcher ({@code versions/<v>/<v>.jar}) or Prism/MultiMC ({@code libraries/com/mojang/minecraft}).
     * Only read here, for vanilla textures; never copied.
     */
    static Optional<Path> findClientJar() {
        Path home = Path.of(System.getProperty("user.home"));
        List<Path> dirs = new ArrayList<>();
        String appData = System.getenv("APPDATA");
        if (appData != null) {
            dirs.add(Path.of(appData, ".minecraft", "versions"));
            dirs.add(Path.of(appData, "PrismLauncher", "libraries", "com", "mojang", "minecraft"));
        }
        dirs.add(home.resolve(".minecraft/versions"));
        dirs.add(home.resolve("Library/Application Support/minecraft/versions"));
        dirs.add(home.resolve(".local/share/PrismLauncher/libraries/com/mojang/minecraft"));
        dirs.add(home.resolve(".local/share/multimc/libraries/com/mojang/minecraft"));
        return newestClient(dirs);
    }

    static Optional<Path> newestClient(List<Path> dirs) {
        List<Path> jars = new ArrayList<>();
        for (Path d : dirs) {
            if (!Files.isDirectory(d)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(d, 2)) {
                files.filter(p -> p.toString().endsWith(".jar") && Files.isRegularFile(p)).forEach(jars::add);
            } catch (IOException | RuntimeException e) {
                // skip this folder
            }
        }
        jars.sort(Comparator.comparing((Path p) -> versionKey(p.getParent().getFileName().toString())).reversed());
        return jars.stream().filter(PackReader::isModernClient).findFirst();
    }

    /** "26.3" → sortable key; release versions compare by their numbers, snapshots sort last. */
    static String versionKey(String version) {
        StringBuilder key = new StringBuilder();
        for (String part : version.split("\\.")) {
            if (!part.matches("\\d{1,6}")) {
                return "";
            }
            key.append(String.format("%06d", Integer.parseInt(part)));
        }
        return key.toString();
    }

    /** Minecraft folders of the vanilla launcher, Lunar, Prism/MultiMC and Modrinth on this OS. */
    static List<Path> minecraftRoots() {
        Path home = Path.of(System.getProperty("user.home"));
        String appData = System.getenv("APPDATA");
        List<Path> roots = new ArrayList<>();
        if (appData != null) {
            Path a = Path.of(appData);
            roots.addAll(List.of(a.resolve(".minecraft"), a.resolve("PrismLauncher/instances"),
                    a.resolve("ModrinthApp/profiles")));
        }
        roots.addAll(List.of(home.resolve(".minecraft"), home.resolve(".lunarclient"),
                home.resolve("Library/Application Support/minecraft"),
                home.resolve(".local/share/PrismLauncher/instances"),
                home.resolve(".local/share/multimc/instances"),
                home.resolve(".local/share/ModrinthApp/profiles"),
                home.resolve("Downloads")));
        return roots;
    }

    /**
     * Pack folders of a root: {@code <root>/<pack folder>}, and for launchers with instances or profiles
     * {@code <root>/<name>/<pack folder>} and {@code <root>/<name>/[.]minecraft/<pack folder>}. Downloads is
     * searched itself. Fixed paths only: walking a whole {@code .minecraft} would read libraries and saves.
     */
    static List<Path> packFolders(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        if (root.getFileName() != null && root.getFileName().toString().equals("Downloads")) {
            return List.of(root);
        }
        List<Path> bases = new ArrayList<>(List.of(root));
        try (Stream<Path> children = Files.list(root)) {
            for (Path child : children.filter(Files::isDirectory).toList()) {
                bases.add(child);
                bases.add(child.resolve(".minecraft"));
                bases.add(child.resolve("minecraft"));
            }
        } catch (IOException | RuntimeException e) {
            // keep the root itself
        }
        List<Path> folders = new ArrayList<>();
        for (Path base : bases) {
            for (String name : PACK_FOLDERS) {
                if (Files.isDirectory(base.resolve(name))) {
                    folders.add(base.resolve(name));
                }
            }
        }
        return folders;
    }
}
