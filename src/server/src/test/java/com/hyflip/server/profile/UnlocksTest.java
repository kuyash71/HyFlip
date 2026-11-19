package com.hyflip.server.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyflip.server.engine.EngineFixtures;
import com.hyflip.server.engine.PriceSource;
import com.hyflip.server.source.neu.NeuItem.Requirement;
import com.hyflip.server.source.neu.NeuItem.Requirement.Kind;
import com.hyflip.server.support.Fixtures;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UnlocksTest {

    static final CollectionCatalog CATALOG = CollectionCatalog.parse(Fixtures.JSON.readTree("""
            {"collections": {"MINING": {"name": "Mining", "items": {
                "MITHRIL_ORE": {"name": "Mithril"}, "GEMSTONE_COLLECTION": {"name": "Gemstone"}}},
              "FISHING": {"name": "Fishing", "items": {"LOTUS": {"name": "Lotus"}}}}}
            """));

    static PlayerContext context(Integer hotm, Map<String, Integer> collections) {
        return new PlayerContext(hotm, 2, false, collections, null, false, "UNKNOWN", null, Duration.ofHours(8),
                PriceSource.ORDER, EngineFixtures.FEES, "CONFIG");
    }

    static final List<Requirement> GEMSTONE_AND_HOTM = List.of(
            new Requirement(Kind.OTHER, "Gemstone", 10), new Requirement(Kind.HOTM, "HotM", 5));

    @Test
    void allRequirementsMustBeMet() {
        assertThat(Unlocks.check(GEMSTONE_AND_HOTM, context(5, Map.of("GEMSTONE_COLLECTION", 10)), CATALOG).state())
                .isEqualTo(Unlocks.State.MET);
        assertThat(Unlocks.check(GEMSTONE_AND_HOTM, context(4, Map.of("GEMSTONE_COLLECTION", 10)), CATALOG).state())
                .isEqualTo(Unlocks.State.LOCKED);
    }

    @Test
    void lockedWinsOverUnknown() {
        assertThat(Unlocks.check(GEMSTONE_AND_HOTM, context(3, Map.of()), CATALOG).state())
                .isEqualTo(Unlocks.State.LOCKED);
        Unlocks.Unlock unknown = Unlocks.check(GEMSTONE_AND_HOTM, context(7, Map.of()), CATALOG);
        assertThat(unknown.state()).isEqualTo(Unlocks.State.UNKNOWN);
        assertThat(unknown.checks().get(0).collectionId()).isEqualTo("GEMSTONE_COLLECTION");
    }

    static PlayerContext linked(Map<String, Integer> collections, Map<String, Integer> slayers) {
        return new PlayerContext(7, 2, false, collections, null, false, "UNKNOWN", null, Duration.ofHours(8),
                PriceSource.ORDER, EngineFixtures.FEES, "PROFILE", true, slayers);
    }

    @Test
    void aLinkedProfileListsEveryCollectionSoAMissingOneIsLocked() {
        Unlocks.Unlock u = Unlocks.check(List.of(new Requirement(Kind.OTHER, "Gemstone", 1)),
                linked(Map.of("MITHRIL_ORE", 9), Map.of()), CATALOG);
        assertThat(u.state()).isEqualTo(Unlocks.State.LOCKED);
        assertThat(u.checks().get(0).player()).isZero();
        // A name the catalog does not know stays unknown even then.
        assertThat(Unlocks.check(List.of(new Requirement(Kind.OTHER, "Catacombs", 5)),
                linked(Map.of(), Map.of()), CATALOG).state()).isEqualTo(Unlocks.State.UNKNOWN);
    }

    @Test
    void collectionsTheProfileApiNeverListsStayUnknownUnlessTyped() {
        List<Requirement> lotus = List.of(new Requirement(Kind.OTHER, "Lotus", 10));
        assertThat(Unlocks.check(lotus, linked(Map.of("MITHRIL_ORE", 9), Map.of()), CATALOG).state())
                .isEqualTo(Unlocks.State.UNKNOWN);
        // Typed in the settings: the context carries it next to the profile's tiers.
        assertThat(Unlocks.check(lotus, linked(Map.of("LOTUS", 10), Map.of()), CATALOG).state())
                .isEqualTo(Unlocks.State.MET);
        assertThat(Unlocks.check(lotus, linked(Map.of("LOTUS", 9), Map.of()), CATALOG).state())
                .isEqualTo(Unlocks.State.LOCKED);
    }

    @Test
    void slayerLevelsComeFromTheProfile() {
        List<Requirement> blaze = List.of(new Requirement(Kind.OTHER, "Blaze Slayer", 7));
        assertThat(Unlocks.check(blaze, linked(Map.of(), Map.of("blaze", 6)), CATALOG).state())
                .isEqualTo(Unlocks.State.LOCKED);
        assertThat(Unlocks.check(blaze, linked(Map.of(), Map.of("blaze", 7)), CATALOG).state())
                .isEqualTo(Unlocks.State.MET);
        // Never fought: level 0.
        assertThat(Unlocks.check(blaze, linked(Map.of(), Map.of()), CATALOG).state()).isEqualTo(Unlocks.State.LOCKED);
        assertThat(Unlocks.slayerBoss("Revenant Slayer")).isEqualTo("zombie");
        assertThat(Unlocks.slayerBoss("Mithril")).isNull();
    }

    @Test
    void slayersAndOtherRequirementsStayUnknown() {
        List<Requirement> slayer = List.of(new Requirement(Kind.OTHER, "Zombie Slayer", 3));
        assertThat(Unlocks.check(slayer, context(10, Map.of()), CATALOG).state()).isEqualTo(Unlocks.State.UNKNOWN);
        assertThat(Unlocks.check(List.of(), context(null, Map.of()), CATALOG)).isEqualTo(Unlocks.Unlock.NONE);
    }

    @Test
    void catalogResolvesNamesAndIds() {
        assertThat(CATALOG.resolve("mithril")).contains("MITHRIL_ORE");
        assertThat(CATALOG.resolve("MITHRIL_ORE")).contains("MITHRIL_ORE");
        assertThat(CATALOG.resolve("Zombie Slayer")).isEmpty();
    }

    @Test
    void electionPerksIncludeTheMinister() {
        Set<String> perks = ElectionPerks.active(Fixtures.JSON.readTree("""
                {"mayor": {"perks": [{"name": "Fishing XP Buff"}],
                           "minister": {"perk": {"name": "Molten Forge"}}}}
                """));
        assertThat(perks).containsExactlyInAnyOrder("Fishing XP Buff", ElectionPerks.MOLTEN_FORGE);
    }
}
