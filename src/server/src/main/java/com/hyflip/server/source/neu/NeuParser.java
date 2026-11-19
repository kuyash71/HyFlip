package com.hyflip.server.source.neu;

import com.hyflip.server.source.neu.NeuItem.Requirement;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Parses an extracted NEU repository ({@code items/*.json}, {@code constants/bazaarstocks.json}).
 * Format checked against the repository on 2026-11-17.
 */
public class NeuParser {

    private static final Logger log = LoggerFactory.getLogger(NeuParser.class);

    private static final Pattern FORMATTING = Pattern.compile("§.");
    private static final Pattern REQUIRES = Pattern.compile("^\\s*Requires:\\s*(.+)$");
    private static final Pattern REQUIREMENT = Pattern.compile("(.+?)\\s+([0-9]+|[IVXLC]+)");
    private static final Pattern GRID = Pattern.compile("[ABC][123]");
    private static final String COINS = "SKYBLOCK_COIN";
    private static final String PET_PREFIX = "[Lvl {LVL}]";
    private static final String[] PET_RARITIES = {"Common", "Uncommon", "Rare", "Epic", "Legendary", "Mythic"};

    private final ObjectMapper json;

    public NeuParser(ObjectMapper json) {
        this.json = json;
    }

    public NeuData parse(Path repo, String commit) throws IOException {
        Map<String, NeuItem> items = new HashMap<>();
        List<NeuRecipe> recipes = new ArrayList<>();
        int failed = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(repo.resolve("items"), "*.json")) {
            for (Path file : files) {
                try (InputStream in = Files.newInputStream(file)) {
                    JsonNode node = json.readTree(in);
                    NeuItem item = item(node);
                    if (item != null) {
                        items.put(item.internalName(), item);
                        recipes.addAll(recipes(node, item.internalName()));
                    }
                } catch (RuntimeException e) {
                    failed++;
                    log.debug("Skipping NEU file {}: {}", file.getFileName(), e.getMessage());
                }
            }
        }
        if (failed > 0) {
            log.warn("Skipped {} unreadable NEU item files", failed);
        }
        return new NeuData(commit, Instant.now(), Map.copyOf(items), List.copyOf(recipes), bazaarStocks(repo));
    }

    static final String ENCHANTED_BOOK = "Enchanted Book";

    private static final Pattern RARITY = Pattern.compile(
            "\\b(VERY SPECIAL|SPECIAL|ADMIN|ULTIMATE|DIVINE|MYTHIC|LEGENDARY|EPIC|RARE|UNCOMMON|COMMON)\\b");
    private static final String[] PET_TIERS = {"COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC"};

    /**
     * The rarity word of the last lore line that has one ("§6§lLEGENDARY BOOTS"); pets take it from
     * the tier in their ID ({@code MOLE;4} is legendary). Null if neither says.
     */
    static String rarity(String internalName, JsonNode lore, boolean pet) {
        if (lore != null && lore.isArray()) {
            for (int i = lore.size() - 1; i >= 0; i--) {
                JsonNode line = lore.get(i);
                String text = line.isString() ? stripFormatting(line.asString()).trim() : "";
                if (text.isEmpty()) {
                    continue;
                }
                Matcher m = RARITY.matcher(text);
                // Recombobulated items wrap the line in obfuscated "a" glyphs: "a MYTHIC ACCESSORY a".
                if (m.find() && text.substring(0, m.start()).matches("(a\\s+)?")) {
                    return m.group(1).replace(' ', '_');
                }
                break;
            }
        }
        int semicolon = internalName.lastIndexOf(';');
        if (pet && semicolon > 0) {
            try {
                int tier = Integer.parseInt(internalName.substring(semicolon + 1));
                if (tier >= 0 && tier < PET_TIERS.length) {
                    return PET_TIERS[tier];
                }
            } catch (NumberFormatException e) {
                // not a pet tier
            }
        }
        return null;
    }

    NeuItem item(JsonNode node) {
        String internal = text(node, "internalname");
        if (internal == null) {
            return null;
        }
        String display = text(node, "displayname");
        String name = display == null ? null : displayName(internal, display);
        if (ENCHANTED_BOOK.equals(name) && internal.contains(";")) {
            name = bookName(node.get("lore"), name);
        }
        return new NeuItem(internal, name, requirements(text(node, "crafttext")), text(node, "island"),
                rarity(internal, node.get("lore"), display != null && stripFormatting(display).trim().startsWith(PET_PREFIX)),
                text(node, "itemid"));
    }

    /**
     * Every enchantment book is called "Enchanted Book"; its enchantment ("Looting VII") is the first
     * lore line after "Combinable in Anvil" and blank lines.
     */
    static String bookName(JsonNode lore, String fallback) {
        if (lore == null || !lore.isArray()) {
            return fallback;
        }
        for (JsonNode line : lore) {
            String text = line.isString() ? stripFormatting(line.asString()).trim() : "";
            if (!text.isEmpty() && !text.startsWith("Combinable")) {
                return text;
            }
        }
        return fallback;
    }

    List<NeuRecipe> recipes(JsonNode node, String internalName) {
        List<NeuRecipe> out = new ArrayList<>();
        JsonNode legacy = node.get("recipe");
        if (legacy != null && legacy.isObject()) {
            out.add(crafting(legacy, internalName));
        }
        JsonNode list = node.get("recipes");
        if (list != null && list.isArray()) {
            for (JsonNode r : list) {
                String type = text(r, "type");
                if (type == null) {
                    continue;
                }
                switch (type) {
                    case "crafting" -> out.add(crafting(r, internalName));
                    case "forge" -> out.add(forge(r, internalName));
                    case "npc_shop" -> out.add(npcShop(r, internalName));
                    default -> {
                        // drops, trade, katgrade: not used by the MVP flippers
                    }
                }
            }
        }
        return out;
    }

    private static final String[] CELLS = {"A1", "A2", "A3", "B1", "B2", "B3", "C1", "C2", "C3"};

    private NeuRecipe crafting(JsonNode r, String internalName) {
        Map<String, Double> inputs = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> slot : r.properties()) {
            if (GRID.matcher(slot.getKey()).matches() && slot.getValue().isString()) {
                addIngredient(inputs, slot.getValue().asString());
            }
        }
        List<String> grid = new ArrayList<>(9);
        for (String cell : CELLS) {
            JsonNode v = r.get(cell);
            String raw = v != null && v.isString() ? v.asString().trim() : "";
            grid.add(raw.isEmpty() ? null : raw);
        }
        return new NeuRecipe(NeuRecipe.Type.CRAFTING, output(r, internalName), count(r), inputs, 0, null,
                internalName, grid);
    }

    private NeuRecipe forge(JsonNode r, String internalName) {
        Map<String, Double> inputs = new LinkedHashMap<>();
        for (JsonNode in : r.path("inputs")) {
            addIngredient(inputs, in.asString());
        }
        double coins = inputs.getOrDefault(COINS, 0.0);
        inputs.remove(COINS);
        long duration = r.path("duration").asLong(0);
        return new NeuRecipe(NeuRecipe.Type.FORGE, output(r, internalName), count(r), inputs, coins, duration,
                internalName);
    }

    private NeuRecipe npcShop(JsonNode r, String internalName) {
        Map<String, Double> inputs = new LinkedHashMap<>();
        for (JsonNode in : r.path("cost")) {
            addIngredient(inputs, in.asString());
        }
        double coins = inputs.getOrDefault(COINS, 0.0);
        inputs.remove(COINS);
        String result = text(r, "result");
        String output = internalName;
        double count = 1;
        if (result != null) {
            Ingredient parsed = Ingredient.parse(result);
            output = parsed.id();
            count = parsed.amount();
        }
        return new NeuRecipe(NeuRecipe.Type.NPC_SHOP, output, count, inputs, coins, null, internalName);
    }

    private Map<String, String> bazaarStocks(Path repo) throws IOException {
        Path file = repo.resolve("constants/bazaarstocks.json");
        Map<String, String> stocks = new HashMap<>();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                for (JsonNode entry : json.readTree(in)) {
                    String stock = text(entry, "stock");
                    String id = text(entry, "id");
                    if (stock != null && id != null) {
                        stocks.put(stock, id);
                    }
                }
            }
        }
        return Map.copyOf(stocks);
    }

    static List<Requirement> requirements(String craftText) {
        if (craftText == null || craftText.isBlank()) {
            return List.of();
        }
        Matcher all = REQUIRES.matcher(stripFormatting(craftText).trim());
        if (!all.matches()) {
            return List.of();
        }
        List<Requirement> out = new ArrayList<>();
        for (String part : all.group(1).split("&")) {
            Matcher m = REQUIREMENT.matcher(part.trim());
            if (!m.matches()) {
                continue;
            }
            String name = m.group(1);
            String level = m.group(2);
            int value = Character.isDigit(level.charAt(0)) ? Integer.parseInt(level) : roman(level);
            Requirement.Kind kind = name.equalsIgnoreCase("HotM") ? Requirement.Kind.HOTM : Requirement.Kind.OTHER;
            out.add(new Requirement(kind, name, value));
        }
        return out;
    }

    static int roman(String s) {
        int total = 0;
        int prev = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            int v = switch (s.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                case 'L' -> 50;
                case 'C' -> 100;
                default -> 0;
            };
            total += v < prev ? -v : v;
            prev = Math.max(prev, v);
        }
        return total;
    }

    /**
     * Strips formatting; pets ("[Lvl {LVL}] Mole", ID {@code MOLE;4}) become "Mole (Legendary)", since
     * one NEU file exists per pet rarity.
     */
    static String displayName(String internalName, String raw) {
        // Private-use characters are icons from the game's resource pack (gemstone symbols); drop them.
        String name = stripFormatting(raw).replaceAll("[\\uE000-\\uF8FF]", "").trim();
        if (!name.startsWith(PET_PREFIX)) {
            return name;
        }
        name = name.substring(PET_PREFIX.length()).trim();
        int semicolon = internalName.lastIndexOf(';');
        if (semicolon >= 0) {
            try {
                int tier = Integer.parseInt(internalName.substring(semicolon + 1));
                if (tier >= 0 && tier < PET_RARITIES.length) {
                    name += " (" + PET_RARITIES[tier] + ")";
                }
            } catch (NumberFormatException e) {
                // not a pet tier
            }
        }
        return name;
    }

    static String stripFormatting(String s) {
        return FORMATTING.matcher(s).replaceAll("");
    }

    private static void addIngredient(Map<String, Double> inputs, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        Ingredient i = Ingredient.parse(raw);
        inputs.merge(i.id(), i.amount(), Double::sum);
    }

    private static String output(JsonNode r, String internalName) {
        String override = text(r, "overrideOutputId");
        return override != null ? override : internalName;
    }

    private static double count(JsonNode r) {
        return r.path("count").asDouble(1);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isString() ? v.asString() : null;
    }

    /** {@code ID:amount}; the amount defaults to 1. NEU IDs never contain {@code :}. */
    record Ingredient(String id, double amount) {

        static Ingredient parse(String raw) {
            int colon = raw.lastIndexOf(':');
            if (colon < 0) {
                return new Ingredient(raw, 1);
            }
            try {
                return new Ingredient(raw.substring(0, colon), Double.parseDouble(raw.substring(colon + 1)));
            } catch (NumberFormatException e) {
                return new Ingredient(raw, 1);
            }
        }
    }
}
