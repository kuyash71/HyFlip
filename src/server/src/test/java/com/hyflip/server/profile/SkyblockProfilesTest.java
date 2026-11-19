package com.hyflip.server.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SkyblockProfilesTest {

    static final String ME = "a41b271f93b040b4a9776e83d4f61918";

    /** Shape of a real response, trimmed to the fields that are read. */
    static final String BODY = """
            {"success": true, "profiles": [
              {"profile_id": "p-orange", "cute_name": "Orange", "selected": false,
               "members": {"%1$s": {"skill_tree": {"experience": {"mining": 1000}}}}},
              {"profile_id": "p-tomato", "cute_name": "Tomato", "selected": true,
               "members": {"%1$s": {
                 "skill_tree": {"experience": {"mining": 21600.0},
                                "nodes": {"mining": {"quick_forge": 11, "toggle_quick_forge": true}}},
                 "slayer": {"slayer_bosses": {"zombie": {"xp": 1000}, "blaze": {"xp": 399999}, "wolf": {"xp": 9}}},
                 "player_data": {"unlocked_coll_tiers": ["MITHRIL_ORE_5", "MITHRIL_ORE_9", "INK_SACK:3_8", "BAD", "X_-1"]},
                 "forge": {"forge_processes": {"forge_1": {
                   "1": {"type": "DRILL_PARTS", "id": "MITHRIL_FUEL_TANK", "startTime": 1794950637194, "slot": 1},
                   "3": {"type": "GEAR", "id": "TITANIUM_CLOAK", "startTime": 1794980135325, "slot": 3}}}}},
                 "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0": {
                   "player_data": {"unlocked_coll_tiers": ["MITHRIL_ORE_7", "MELON_9", "MELON_-1"]}}}}
            ]}""".formatted(ME);

    static JsonNode body() {
        return JsonMapper.builder().build().readTree(BODY);
    }

    @Test
    void readsTheActiveProfile() {
        LinkedProfile p = SkyblockProfiles.parse(body(), "kuyashe", ME, "", 42);
        assertThat(p.cuteName()).isEqualTo("Tomato");
        assertThat(p.profileNames()).containsExactly("Orange", "Tomato");
        assertThat(p.hotmLevel()).isEqualTo(3);
        assertThat(p.quickForgeLevel()).isEqualTo(11);
        assertThat(p.quickForgeReduction()).isEqualTo(0.155);
        // A co-op shares collections: the partner's Melon IX counts, the lower Mithril does not.
        assertThat(p.collections()).containsEntry("MITHRIL_ORE", 9).containsEntry("INK_SACK:3", 8)
                .containsEntry("MELON", 9).hasSize(3);
        assertThat(p.forge()).containsExactly(
                new LinkedProfile.ForgeProcess(1, "MITHRIL_FUEL_TANK", 1794950637194L),
                new LinkedProfile.ForgeProcess(3, "TITANIUM_CLOAK", 1794980135325L));
        assertThat(p.highestForgeSlot()).isEqualTo(3);
        assertThat(p.forgeKnown()).isTrue();
        assertThat(p.fetchedAt()).isEqualTo(42);
        assertThat(p.slayers()).containsEntry("zombie", 4).containsEntry("blaze", 7).containsEntry("wolf", 0)
                .containsEntry("spider", 0).containsEntry("vampire", 0);
    }

    @Test
    void selectsAProfileByName() {
        LinkedProfile p = SkyblockProfiles.parse(body(), "kuyashe", ME, "orange", 0);
        assertThat(p.profileId()).isEqualTo("p-orange");
        assertThat(p.hotmLevel()).isEqualTo(1);
        assertThat(p.quickForgeReduction()).isNull();
        assertThat(p.forge()).isEmpty();
        assertThat(p.forgeKnown()).isFalse();
        assertThatThrownBy(() -> SkyblockProfiles.parse(body(), "kuyashe", ME, "Banana", 0))
                .hasMessageContaining("Orange, Tomato");
        assertThatThrownBy(() -> SkyblockProfiles.parse(body(), "kuyashe", "00000000000000000000000000000000", "", 0))
                .isInstanceOf(SkyblockProfiles.ProfileException.class);
    }

    @Test
    void hotmLevelsAndQuickForge() {
        assertThat(List.of(0d, 2_999d, 3_000d, 37_000d, 346_999d, 347_000d, 1_247_000d, 9e9).stream()
                .map(SkyblockProfiles::hotmLevel).toList()).containsExactly(1, 1, 2, 4, 6, 7, 10, 10);
        assertThat(SkyblockProfiles.quickForge(1)).isEqualTo(0.105);
        assertThat(SkyblockProfiles.quickForge(19)).isEqualTo(0.195);
        assertThat(SkyblockProfiles.quickForge(20)).isEqualTo(0.30);
    }
}
