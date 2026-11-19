package com.hyflip.server.profile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

/** Reads the parts of {@code /v2/skyblock/profiles} that the flippers use. */
public final class SkyblockProfiles {

    /**
     * Total HOTM experience needed for each level (index 0 = level 1). Levels 8 to 10 came with
     * the 2024 mining update.
     */
    static final double[] HOTM_XP = {0, 3_000, 12_000, 37_000, 97_000, 197_000, 347_000, 557_000, 847_000, 1_247_000};

    /** Quick Forge: 10% + 0.5% per level up to level 19; level 20 (max) gives 30%. */
    static final int QUICK_FORGE_MAX_LEVEL = 20;

    private SkyblockProfiles() {
    }

    /** The profile could not be used; the message is shown to the player. */
    public static class ProfileException extends RuntimeException {
        public ProfileException(String message) {
            super(message);
        }
    }

    /**
     * @param root        the profiles response
     * @param username    the player's name (kept with the result)
     * @param uuid        the member whose personal data is read
     * @param profileName profile cute name; blank selects the active profile
     */
    public static LinkedProfile parse(JsonNode root, String username, String uuid, String profileName, long now) {
        if (!root.path("success").asBoolean(false)) {
            throw new ProfileException("Hypixel returned success=false: " + root.path("cause").asString(""));
        }
        List<JsonNode> profiles = new ArrayList<>();
        root.path("profiles").forEach(profiles::add);
        if (profiles.isEmpty()) {
            throw new ProfileException(username + " has no SkyBlock profiles");
        }
        List<String> names = profiles.stream().map(p -> p.path("cute_name").asString("")).toList();
        JsonNode profile = profiles.stream()
                .filter(p -> profileName == null || profileName.isBlank()
                        ? p.path("selected").asBoolean(false)
                        : p.path("cute_name").asString("").equalsIgnoreCase(profileName.trim()))
                .findFirst()
                .orElseThrow(() -> new ProfileException(profileName == null || profileName.isBlank()
                        ? "No active profile found; choose one of " + String.join(", ", names)
                        : "No profile named " + profileName + "; " + username + " has " + String.join(", ", names)));
        JsonNode member = profile.path("members").path(uuid);
        if (member.isMissingNode()) {
            throw new ProfileException("The member is not in profile " + profile.path("cute_name").asString(""));
        }

        double xp = member.path("skill_tree").path("experience").path("mining").asDouble(
                member.path("mining_core").path("experience").asDouble(-1));
        Integer hotm = xp < 0 ? null : hotmLevel(xp);

        JsonNode mining = member.path("skill_tree").path("nodes").path("mining");
        JsonNode qfNode = mining.has("quick_forge") ? mining.path("quick_forge")
                : member.path("mining_core").path("nodes").path("forge_time");
        Integer qfLevel = qfNode.isNumber() && qfNode.asInt() > 0 ? qfNode.asInt() : null;
        boolean qfEnabled = mining.path("toggle_quick_forge").asBoolean(true);
        Double qf = qfLevel == null ? null : qfEnabled ? quickForge(qfLevel) : 0.0;

        // Collections are shared in a co-op, but each member's list only catches up when they play: take the highest.
        Map<String, Integer> tiers = new TreeMap<>();
        for (JsonNode m : profile.path("members")) {
            for (JsonNode t : m.path("player_data").path("unlocked_coll_tiers")) {
                collectionTier(t.asString("")).ifPresent(e -> tiers.merge(e.getKey(), e.getValue(), Math::max));
            }
        }

        JsonNode processes = member.path("forge").path("forge_processes");
        List<LinkedProfile.ForgeProcess> forge = new ArrayList<>();
        for (JsonNode forgeType : processes) {
            for (JsonNode p : forgeType) {
                String id = p.path("id").asString("");
                long start = p.path("startTime").asLong(0);
                int slot = p.path("slot").asInt(0);
                if (!id.isBlank() && start > 0 && slot > 0) {
                    forge.add(new LinkedProfile.ForgeProcess(slot, id, start));
                }
            }
        }
        forge.sort(Comparator.comparingInt(LinkedProfile.ForgeProcess::slot));

        return new LinkedProfile(username, uuid, profile.path("profile_id").asString(""),
                profile.path("cute_name").asString(""), names, hotm, Math.max(xp, 0), qfLevel, qf, Map.copyOf(tiers),
                List.copyOf(forge), member.has("forge"), now, coins(member.path("currencies").path("coin_purse")),
                coins(profile.path("banking").path("balance")), slayers(member.path("slayer").path("slayer_bosses")));
    }

    private static Double coins(JsonNode node) {
        return node.isNumber() ? node.asDouble() : null;
    }

    /** Total slayer XP needed for each level, per boss. */
    static final Map<String, long[]> SLAYER_XP = Map.of(
            "zombie", new long[] {5, 15, 200, 1_000, 5_000, 20_000, 100_000, 400_000, 1_000_000},
            "spider", new long[] {5, 25, 200, 1_000, 5_000, 20_000, 100_000, 400_000, 1_000_000},
            "wolf", new long[] {10, 30, 250, 1_500, 5_000, 20_000, 100_000, 400_000, 1_000_000},
            "enderman", new long[] {10, 30, 250, 1_500, 5_000, 20_000, 100_000, 400_000, 1_000_000},
            "blaze", new long[] {10, 30, 250, 1_500, 5_000, 20_000, 100_000, 400_000, 1_000_000},
            "vampire", new long[] {20, 75, 240, 840, 2_400});

    /** Level per slayer boss from its XP; a boss never fought is level 0. */
    static Map<String, Integer> slayers(JsonNode bosses) {
        Map<String, Integer> levels = new TreeMap<>();
        SLAYER_XP.forEach((boss, table) -> {
            long xp = bosses.path(boss).path("xp").asLong(0);
            int level = 0;
            while (level < table.length && xp >= table[level]) {
                level++;
            }
            levels.put(boss, level);
        });
        return Map.copyOf(levels);
    }

    static int hotmLevel(double xp) {
        int level = 1;
        for (int i = 1; i < HOTM_XP.length; i++) {
            if (xp >= HOTM_XP[i]) {
                level = i + 1;
            }
        }
        return level;
    }

    static double quickForge(int level) {
        if (level >= QUICK_FORGE_MAX_LEVEL) {
            return 0.30;
        }
        return Math.round((10 + 0.5 * level) * 10) / 1000.0;
    }

    /** {@code "MITHRIL_ORE_9"} or {@code "INK_SACK:3_8"}: collection ID, underscore, tier. */
    static Optional<Map.Entry<String, Integer>> collectionTier(String entry) {
        int cut = entry.lastIndexOf('_');
        if (cut <= 0 || cut == entry.length() - 1) {
            return Optional.empty();
        }
        try {
            int tier = Integer.parseInt(entry.substring(cut + 1));
            return tier < 0 ? Optional.empty()
                    : Optional.of(Map.entry(entry.substring(0, cut).toUpperCase(Locale.ROOT), tier));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
