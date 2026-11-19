package com.hyflip.server.profile;

import com.hyflip.server.source.neu.NeuItem.Requirement;
import java.util.ArrayList;
import java.util.List;

/** Checks NEU recipe requirements against the player's context. */
public final class Unlocks {

    private Unlocks() {
    }

    /**
     * Collections the profile API never lists in {@code unlocked_coll_tiers}, not even in its raw counts (checked on
     * 14 players in Nov 2026, including a 12-member co-op): a missing one says nothing, so it stays UNKNOWN unless
     * the player typed its tier in the settings.
     */
    public static final java.util.Set<String> NOT_IN_PROFILE_API = java.util.Set.of("LOTUS", "CHILI_PEPPER",
            "DOUBLE_PLANT", "HELIX_LOG", "HONEYCOMB", "MOONFLOWER", "RUBY_VEILSHROOM", "WILD_ROSE");

    /** MET: all requirements met. LOCKED: at least one is not. UNKNOWN: the context lacks a value. */
    public enum State { MET, LOCKED, UNKNOWN }

    /**
     * @param requirement  text such as "HotM 5" or "Mithril 10"
     * @param required     level or tier needed
     * @param player       the player's level or tier, null if unknown
     * @param collectionId matched collection ID for collection requirements, null otherwise
     */
    public record Check(String requirement, Requirement.Kind kind, int required, Integer player,
                        String collectionId, State state) {
    }

    public record Unlock(State state, List<Check> checks) {

        public static final Unlock NONE = new Unlock(State.MET, List.of());
    }

    public static Unlock check(List<Requirement> requirements, PlayerContext context, CollectionCatalog catalog) {
        if (requirements.isEmpty()) {
            return Unlock.NONE;
        }
        List<Check> checks = new ArrayList<>();
        for (Requirement r : requirements) {
            checks.add(check(r, context, catalog));
        }
        State overall = checks.stream().anyMatch(c -> c.state() == State.LOCKED) ? State.LOCKED
                : checks.stream().anyMatch(c -> c.state() == State.UNKNOWN) ? State.UNKNOWN : State.MET;
        return new Unlock(overall, List.copyOf(checks));
    }

    static Check check(Requirement r, PlayerContext context, CollectionCatalog catalog) {
        String text = r.name() + " " + r.level();
        if (r.kind() == Requirement.Kind.HOTM) {
            return new Check(text, r.kind(), r.level(), context.hotmLevel(), null,
                    compare(context.hotmLevel(), r.level()));
        }
        String boss = slayerBoss(r.name());
        if (boss != null) {
            Integer level = context.slayers() == null ? null : context.slayers().getOrDefault(boss, 0);
            return new Check(text, r.kind(), r.level(), level, null, compare(level, r.level()));
        }
        // Collections the catalog knows; anything else (skills, dungeons, ...) stays UNKNOWN. A linked profile
        // lists every unlocked tier, so a collection missing from it has not reached tier 1.
        String collection = catalog.resolve(r.name()).orElse(null);
        Integer tier = collection == null ? null : context.collections().get(collection);
        if (tier == null && collection != null && context.collectionsComplete()
                && !NOT_IN_PROFILE_API.contains(collection)) {
            tier = 0;
        }
        return new Check(text, r.kind(), r.level(), tier, collection, compare(tier, r.level()));
    }

    /** "Zombie Slayer" → "zombie"; null if the requirement is not a slayer. */
    static String slayerBoss(String name) {
        String n = name.trim().toLowerCase(java.util.Locale.ROOT);
        if (!n.endsWith(" slayer")) {
            return null;
        }
        String boss = n.substring(0, n.length() - " slayer".length()).trim();
        return switch (boss) {
            case "revenant", "zombie" -> "zombie";
            case "tarantula", "spider" -> "spider";
            case "sven", "wolf" -> "wolf";
            case "voidgloom", "enderman" -> "enderman";
            case "inferno", "blaze" -> "blaze";
            case "riftstalker", "vampire" -> "vampire";
            default -> null;
        };
    }

    private static State compare(Integer have, int need) {
        if (have == null) {
            return State.UNKNOWN;
        }
        return have >= need ? State.MET : State.LOCKED;
    }
}
