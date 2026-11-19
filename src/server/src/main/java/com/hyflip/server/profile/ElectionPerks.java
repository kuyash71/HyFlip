package com.hyflip.server.profile;

import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Names of the perks currently active from the mayor and the minister (election resource). */
public final class ElectionPerks {

    public static final String MOLTEN_FORGE = "Molten Forge";

    private ElectionPerks() {
    }

    /** Parses {@code /v2/resources/skyblock/election}. */
    public static Set<String> active(JsonNode root) {
        Set<String> perks = new HashSet<>();
        JsonNode mayor = root.path("mayor");
        for (JsonNode perk : mayor.path("perks")) {
            add(perks, perk);
        }
        add(perks, mayor.path("minister").path("perk"));
        return Set.copyOf(perks);
    }

    private static void add(Set<String> perks, JsonNode perk) {
        JsonNode name = perk.get("name");
        if (name != null && name.isString()) {
            perks.add(name.asString());
        }
    }
}
