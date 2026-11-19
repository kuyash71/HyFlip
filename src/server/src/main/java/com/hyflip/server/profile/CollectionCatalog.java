package com.hyflip.server.profile;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Collection IDs and names from the Hypixel collections resource, so a NEU requirement such as
 * "Mithril X" can be matched to the {@code MITHRIL_ORE} collection.
 */
public final class CollectionCatalog {

    public static final CollectionCatalog EMPTY = new CollectionCatalog(Map.of(), Map.of());

    private final Map<String, String> idByName;
    private final Map<String, String> nameById;

    private CollectionCatalog(Map<String, String> idByName, Map<String, String> nameById) {
        this.idByName = idByName;
        this.nameById = nameById;
    }

    /** Parses {@code /v2/resources/skyblock/collections}. */
    public static CollectionCatalog parse(JsonNode root) {
        Map<String, String> byName = new HashMap<>();
        Map<String, String> byId = new HashMap<>();
        for (JsonNode category : root.path("collections")) {
            for (Map.Entry<String, JsonNode> item : category.path("items").properties()) {
                JsonNode name = item.getValue().get("name");
                if (name != null && name.isString()) {
                    byName.put(key(name.asString()), item.getKey());
                    byId.put(item.getKey(), name.asString());
                }
            }
        }
        return new CollectionCatalog(Map.copyOf(byName), Map.copyOf(byId));
    }

    public boolean isEmpty() {
        return nameById.isEmpty();
    }

    /** Collection ID for a display name ("Mithril") or an ID ("MITHRIL_ORE"), any case. */
    public Optional<String> resolve(String nameOrId) {
        if (nameOrId == null) {
            return Optional.empty();
        }
        String upper = nameOrId.trim().toUpperCase(Locale.ROOT);
        if (nameById.containsKey(upper)) {
            return Optional.of(upper);
        }
        return Optional.ofNullable(idByName.get(key(nameOrId)));
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
