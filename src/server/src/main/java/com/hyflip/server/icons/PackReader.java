package com.hyflip.server.icons;

import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads item icons from a resource pack zip in the 1.21.4+ layout: an item definition
 * ({@code assets/<ns>/items/<path>.json}) points to a model ({@code models/<path>.json}), whose texture layers
 * ({@code textures/<path>.png}) make the icon. Only the item's default look is used: the {@code on_false} branch of a
 * condition, the fallback of a select, the first part of a composite. Animated textures give their first frame.
 *
 * <p>Like the game, it reads a stack of sources, the first that has a file winning: the SkyBlock pack over the
 * player's own Minecraft client jar, which supplies vanilla textures and the parents the pack's models use.
 */
final class PackReader implements Closeable {

    /** The namespace the SkyBlock pack keeps its items in. */
    static final String NAMESPACE = "hypixel_skyblock";
    private static final String DEFINITIONS = "assets/" + NAMESPACE + "/items/";
    /** Pack textures are a few KB; anything far bigger is not an item icon. */
    private static final long MAX_ENTRY_BYTES = 4L << 20;
    private static final int MAX_DEPTH = 12;
    private static final Pattern LAYER = Pattern.compile("layer(\\d+)");

    private final List<ZipFile> zips = new ArrayList<>();
    private final ObjectMapper json;

    PackReader(Path file, ObjectMapper json) throws IOException {
        this(List.of(file), json);
    }

    /** @param files the sources, highest priority first */
    PackReader(List<Path> files, ObjectMapper json) throws IOException {
        this.json = json;
        try {
            for (Path f : files) {
                zips.add(new ZipFile(f.toFile()));
            }
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    /** True if the zip is the Hypixel SkyBlock pack (it holds the {@code hypixel_skyblock} namespace). */
    static boolean isSkyBlockPack(Path file) {
        try (ZipFile z = new ZipFile(file.toFile())) {
            return z.getEntry("pack.mcmeta") != null
                    && z.stream().anyMatch(e -> e.getName().startsWith(DEFINITIONS));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** The pack's description from {@code pack.mcmeta}, or an empty string. */
    String description() {
        JsonNode meta = read("pack.mcmeta");
        JsonNode d = meta == null ? null : meta.path("pack").path("description");
        if (d == null || d.isMissingNode() || d.isNull()) {
            return "";
        }
        return d.isString() ? d.asString() : d.path("text").asString("");
    }

    /** Item model IDs ({@code hypixel_skyblock:item/fishing/foo}) of every item definition in the pack. */
    List<String> itemModels() {
        List<String> models = new ArrayList<>();
        zips.get(0).stream().map(ZipEntry::getName)
                .filter(n -> n.startsWith(DEFINITIONS) && n.endsWith(".json"))
                .sorted()
                .forEach(n -> models.add(NAMESPACE + ":" + n.substring(DEFINITIONS.length(), n.length() - 5)));
        return models;
    }

    /**
     * The icon of an item model ID as Hypixel's items API gives it ({@code item_model}), at the texture's own size.
     * Empty when the pack does not draw that item (vanilla items, player heads, missing textures).
     */
    Optional<BufferedImage> icon(String itemModel) {
        Id id = Id.parse(itemModel);
        JsonNode definition = read("assets/" + id.ns + "/items/" + id.path + ".json");
        String model = definition != null ? defaultModel(definition.path("model"), 0) : itemModel;
        if (model == null) {
            return Optional.empty();
        }
        Map<String, String> textures = new HashMap<>();
        List<String> chain = new ArrayList<>();
        List<String> layers = layers(Id.parse(model), textures, chain, 0);
        if (chain.stream().anyMatch(PackReader::isCube)) {
            BufferedImage top = texture(resolve(textures, textures.get("up"), 0));
            BufferedImage left = texture(resolve(textures, textures.get("north"), 0));
            BufferedImage right = texture(resolve(textures, textures.get("east"), 0));
            if (top != null && left != null && right != null) {
                return Optional.of(isometric(top, left, right));
            }
        }
        List<BufferedImage> images = new ArrayList<>();
        for (String texture : layers) {
            Id t = Id.parse(texture);
            BufferedImage image = image("assets/" + t.ns + "/textures/" + t.path + ".png");
            if (image != null) {
                images.add(image);
            }
        }
        return images.isEmpty() ? Optional.empty() : Optional.of(compose(images));
    }

    /** Follows an item definition's model tree down to the model the item shows by default. */
    static String defaultModel(JsonNode node, int depth) {
        if (node == null || node.isMissingNode() || depth > MAX_DEPTH) {
            return null;
        }
        String type = node.path("type").asString("").replace("minecraft:", "");
        return switch (type) {
            case "model" -> node.path("model").asString(null);
            case "condition" -> defaultModel(node.path("on_false"), depth + 1);
            case "select" -> node.has("fallback") ? defaultModel(node.path("fallback"), depth + 1)
                    : defaultModel(node.path("cases").path(0).path("model"), depth + 1);
            case "range_dispatch" -> node.has("fallback") ? defaultModel(node.path("fallback"), depth + 1)
                    : defaultModel(node.path("entries").path(0).path("model"), depth + 1);
            case "composite" -> defaultModel(node.path("models").path(0), depth + 1);
            default -> null; // "special" (heads, banners, chests) and "empty" have no texture to show
        };
    }

    /**
     * Texture IDs of a model, bottom layer first: {@code layer0, layer1, ...} for flat items, or one texture of a 3D
     * model ({@code particle} or the first one). Texture variables of parents are merged, the child's winning.
     */
    private List<String> layers(Id model, Map<String, String> textures, List<String> chain, int depth) {
        if (depth > MAX_DEPTH) {
            return List.of();
        }
        chain.add(model.ns + ":" + model.path);
        JsonNode node = read("assets/" + model.ns + "/models/" + model.path + ".json");
        if (node == null) {
            // Vanilla parents (item/generated, item/handheld) are not in the pack: the variables are complete.
            return pick(textures);
        }
        for (Map.Entry<String, JsonNode> e : node.path("textures").properties()) {
            textures.putIfAbsent(e.getKey(), e.getValue().asString(""));
        }
        String parent = node.path("parent").asString(null);
        return parent == null ? pick(textures) : layers(Id.parse(parent), textures, chain, depth + 1);
    }

    private static List<String> pick(Map<String, String> textures) {
        List<Map.Entry<Integer, String>> layers = new ArrayList<>();
        for (String key : textures.keySet()) {
            Matcher m = LAYER.matcher(key);
            if (m.matches()) {
                layers.add(Map.entry(Integer.parseInt(m.group(1)), key));
            }
        }
        List<String> keys = new ArrayList<>();
        if (!layers.isEmpty()) {
            layers.sort(Map.Entry.comparingByKey());
            layers.forEach(l -> keys.add(l.getValue()));
        } else if (textures.containsKey("particle")) {
            keys.add("particle");
        } else {
            textures.keySet().stream().sorted(Comparator.naturalOrder()).findFirst().ifPresent(keys::add);
        }
        List<String> resolved = new ArrayList<>();
        for (String key : keys) {
            String value = resolve(textures, textures.get(key), 0);
            if (value != null && !value.isBlank()) {
                resolved.add(value);
            }
        }
        return resolved;
    }

    /** Resolves {@code #name} references between texture variables. */
    private static String resolve(Map<String, String> textures, String value, int depth) {
        if (value == null || depth > MAX_DEPTH) {
            return null;
        }
        return value.startsWith("#") ? resolve(textures, textures.get(value.substring(1)), depth + 1) : value;
    }

    /** Full-block models: {@code block/cube} and the templates built on it (cube_all, cube_column, orientable...). */
    static boolean isCube(String model) {
        return model.equals("minecraft:block/cube");
    }

    private BufferedImage texture(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        Id t = Id.parse(id);
        return image("assets/" + t.ns + "/textures/" + t.path + ".png");
    }

    /**
     * A block as the inventory shows it: a cube seen from above, the top face lit fully, the left (north) face at
     * 80% and the right (east) face at 60%. Twice the texture size, each pixel mapped back to its texel, so the
     * faces stay sharp pixel art.
     */
    static BufferedImage isometric(BufferedImage top, BufferedImage left, BufferedImage right) {
        int n = top.getWidth() * 2;
        double q = n / 4.0;
        double h = n / 2.0;
        BufferedImage out = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                double px = x + 0.5;
                double py = y + 0.5;
                int argb = face(top, 1.0, px, py, 0, q, h, -q, h, q);
                if (argb == 0) {
                    argb = face(left, 0.8, px, py, 0, q, h, q, 0, h);
                }
                if (argb == 0) {
                    argb = face(right, 0.6, px, py, h, h, h, -q, 0, h);
                }
                out.setRGB(x, y, argb);
            }
        }
        return out;
    }

    /**
     * The shaded texel of a face at point (px, py), or 0 outside it. The face is the parallelogram from origin
     * (ox, oy) along a = (ax, ay) for the texture's x and b = (bx, by) for its y.
     */
    private static int face(BufferedImage tex, double shade, double px, double py, double ox, double oy,
                            double ax, double ay, double bx, double by) {
        double det = ax * by - ay * bx;
        double dx = px - ox;
        double dy = py - oy;
        double s = (dx * by - dy * bx) / det;
        double t = (ax * dy - ay * dx) / det;
        if (s < 0 || s >= 1 || t < 0 || t >= 1) {
            return 0;
        }
        int w = tex.getWidth();
        int argb = tex.getRGB((int) (s * w), (int) (t * Math.min(w, tex.getHeight())));
        int a = argb >>> 24;
        if (a == 0) {
            return 0;
        }
        int r = (int) (((argb >> 16) & 0xff) * shade);
        int g = (int) (((argb >> 8) & 0xff) * shade);
        int b = (int) ((argb & 0xff) * shade);
        return a << 24 | r << 16 | g << 8 | b;
    }

    /** Draws the layers over each other at the size of the first; each layer's first frame only. */
    private static BufferedImage compose(List<BufferedImage> images) {
        int size = images.get(0).getWidth();
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        var g = out.createGraphics();
        for (BufferedImage image : images) {
            int w = image.getWidth();
            // An animation stacks its frames vertically; the first frame is the top square.
            g.drawImage(image, 0, 0, size, size, 0, 0, w, Math.min(w, image.getHeight()), null);
        }
        g.dispose();
        return out;
    }

    private JsonNode read(String name) {
        byte[] bytes = bytes(name);
        if (bytes == null) {
            return null;
        }
        try {
            return json.readTree(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private BufferedImage image(String name) {
        byte[] bytes = bytes(name);
        if (bytes == null) {
            return null;
        }
        try {
            BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            return image == null || image.getWidth() == 0 || image.getWidth() > 512 ? null : image;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private byte[] bytes(String name) {
        for (ZipFile zip : zips) {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null || entry.isDirectory()) {
                continue;
            }
            if (entry.getSize() > MAX_ENTRY_BYTES) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                byte[] bytes = in.readNBytes((int) MAX_ENTRY_BYTES + 1);
                return bytes.length > MAX_ENTRY_BYTES ? null : bytes;
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    /** True if the client jar has the 1.21.4+ item definitions this reader understands. */
    static boolean isModernClient(Path jar) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            return z.getEntry("assets/minecraft/items/stone.json") != null;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public void close() throws IOException {
        IOException failed = null;
        for (ZipFile zip : zips) {
            try {
                zip.close();
            } catch (IOException e) {
                failed = e;
            }
        }
        if (failed != null) {
            throw failed;
        }
    }

    /** A resource ID {@code namespace:path}; a bare path is in the {@code minecraft} namespace. */
    record Id(String ns, String path) {
        static Id parse(String id) {
            int colon = id.indexOf(':');
            return colon < 0 ? new Id("minecraft", id) : new Id(id.substring(0, colon), id.substring(colon + 1));
        }
    }
}
