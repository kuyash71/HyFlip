package com.hyflip.server.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hyflip.server.config.HyFlipProperties;
import com.hyflip.server.mapping.ItemRegistry;
import com.hyflip.server.source.coflnet.CoflnetApi;
import com.hyflip.server.source.coflnet.CoflnetException;
import com.hyflip.server.source.coflnet.SoldAuction;
import com.hyflip.server.storage.AuctionRepository;
import com.hyflip.server.support.DatabaseTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "hyflip.collection.tracked-auction-items=HYPERION,GOLDEN_DRAGON;4")
class CoflnetBackfillCollectorTest extends DatabaseTest {

    static final Instant NOW = Instant.parse("2026-11-18T06:00:00Z");

    @Autowired AuctionsCollector auctionsCollector;
    @Autowired ItemRegistry registry;
    @Autowired AuctionRepository auctions;
    @Autowired JdbcClient jdbc;

    final List<String> requested = new ArrayList<>();
    RuntimeException failure;
    final List<Duration> pauses = new ArrayList<>();

    final CoflnetApi fake = tag -> {
        requested.add(tag);
        if (failure != null) {
            throw failure;
        }
        return List.of(
                new SoldAuction("745cb0439ba94c17be2d02ba6285d235", tag, 850_000_000, "2026-11-18T05:30:08", true, 1,
                        List.of(), Map.of("uid", "a")),
                new SoldAuction("845cb0439ba94c17be2d02ba6285d235", tag, 1_200_000_000, "2026-11-17T05:30:08", true, 1,
                        List.of(Map.of("type", "sharpness", "level", 6)), Map.of("hpc", "10")),
                new SoldAuction("945cb0439ba94c17be2d02ba6285d235", "OTHER", 1, "2026-11-17T05:30:08", true, 1,
                        List.of(), Map.of()));
    };

    CoflnetBackfillCollector collector(Instant now) {
        HyFlipProperties.Coflnet config = new HyFlipProperties.Coflnet(true, "http://unused",
                Duration.ofMinutes(5), 40, Duration.ofSeconds(3), Duration.ofHours(24), "");
        return new CoflnetBackfillCollector(fake, auctionsCollector, registry, auctions, config,
                Clock.fixed(now, ZoneOffset.UTC), pauses::add);
    }

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM auction_sale").update();
        jdbc.sql("DELETE FROM coflnet_backfill").update();
    }

    @Test
    void storesSalesWithTheirConditionAndWaitsBeforeRefetching() throws Exception {
        assertThat(collector(NOW).collect()).isEqualTo(2);
        assertThat(requested).as("pets are named differently on Coflnet").containsExactly("HYPERION");
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT s.auction_id, s.clean, s.source, s.ended_at FROM auction_sale s JOIN item i ON i.id = s.item_id
                WHERE i.canonical_id = 'HYPERION' ORDER BY s.auction_id
                """).query().listOfRows();
        assertThat(rows).extracting(r -> ((Number) r.get("clean")).intValue()).containsExactly(1, 0);
        assertThat(rows).extracting(r -> r.get("source")).containsOnly("COFLNET");
        assertThat(((Number) rows.get(0).get("ended_at")).longValue())
                .isEqualTo(Instant.parse("2026-11-18T05:30:08Z").toEpochMilli());

        requested.clear();
        assertThat(collector(NOW.plus(Duration.ofHours(1))).collect()).isZero();
        assertThat(requested).isEmpty();
        assertThat(collector(NOW.plus(Duration.ofHours(25))).collect()).as("already stored").isZero();
        assertThat(requested).containsExactly("HYPERION");
    }

    @Test
    void aRateLimitOnTheFirstRequestFailsTheRun() {
        failure = new CoflnetException(429, "slow down");
        assertThatThrownBy(() -> collector(NOW).collect()).isInstanceOf(CoflnetException.class);
        assertThat(auctions.backfilledAt()).isEmpty();
    }
}
