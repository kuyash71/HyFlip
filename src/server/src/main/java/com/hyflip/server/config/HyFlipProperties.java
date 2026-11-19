package com.hyflip.server.config;

import com.hyflip.server.engine.PriceSource;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Typed view of the user config file ({@code config.yaml}, root key {@code hyflip}).
 *
 * <p>See {@code docs/configuration.md} for every field. Runtime values that the desktop app
 * passes on start ({@code dataDir}, {@code authToken}) also live here.
 *
 * @param dataDir    directory that holds the SQLite database and caches
 * @param authToken  per-launch token the desktop app sends in {@code X-HyFlip-Token}; blank
 *                   disables the check (development only)
 * @param hypixel    Hypixel API settings
 * @param player     player identity and manual personal context
 * @param trading    budget, tax, fee and price source settings
 * @param forge      forge speed and session settings
 * @param npc        NPC Flipper limits
 * @param bazaar     Bazaar Flipper fill estimate and alert threshold
 * @param alerts     data-quality alert thresholds
 * @param display    how times are shown
 * @param collection polling intervals
 * @param retention  history retention
 * @param neu        NEU repository download
 * @param coflnet    sale history backfill
 */
@ConfigurationProperties("hyflip")
public record HyFlipProperties(
        @DefaultValue("./data") Path dataDir,
        @DefaultValue("") String authToken,
        @DefaultValue Hypixel hypixel,
        @DefaultValue Player player,
        @DefaultValue Trading trading,
        @DefaultValue Forge forge,
        @DefaultValue Npc npc,
        @DefaultValue Bazaar bazaar,
        @DefaultValue Alerts alerts,
        @DefaultValue Display display,
        @DefaultValue Collection collection,
        @DefaultValue Retention retention,
        @DefaultValue Neu neu,
        @DefaultValue Coflnet coflnet) {

    /**
     * @param apiKey         personal key from the Hypixel Developer Dashboard (profile features only)
     * @param apiKeyIssuedAt when the key was created; used to warn before it expires
     * @param apiKeyLifetime lifetime of a personal key
     * @param baseUrl        Hypixel API base URL
     * @param mojangUrl      Mojang API base URL (username to UUID)
     * @param profileInterval how often the linked SkyBlock profile is read again
     */
    public record Hypixel(
            @DefaultValue("") String apiKey,
            Instant apiKeyIssuedAt,
            @DefaultValue("3d") Duration apiKeyLifetime,
            @DefaultValue("https://api.hypixel.net") String baseUrl,
            @DefaultValue("https://api.mojang.com") String mojangUrl,
            @DefaultValue("10m") Duration profileInterval) {
    }

    /**
     * @param username    Minecraft username, resolved to a UUID through the Mojang API
     * @param profile     SkyBlock profile name (cute name such as "Apple"); blank selects the active one
     * @param coopMember  co-op member whose personal data is read; blank means the player
     * @param hotmLevel   manual HOTM level, used when the profile API is not linked
     * @param forgeSlots  manual forge slot count, used when the profile API is not linked (2 if unset)
     * @param collections manual collection tiers by collection ID ({@code MITHRIL_ORE}) or name ({@code Mithril})
     */
    public record Player(
            @DefaultValue("") String username,
            @DefaultValue("") String profile,
            @DefaultValue("") String coopMember,
            Integer hotmLevel,
            Integer forgeSlots,
            @DefaultValue Map<String, Integer> collections) {
    }

    /**
     * @param budget        coins available for flips; null means unlimited
     * @param bazaarTaxRate Bazaar sell tax as a fraction (depends on the Bazaar Flipper upgrade)
     * @param priceSource   how Bazaar prices are taken
     * @param auctionFees   Auction House listing fee tiers and claim tax
     */
    public record Trading(
            BigDecimal budget,
            @DefaultValue("0.0125") BigDecimal bazaarTaxRate,
            @DefaultValue("ORDER") PriceSource priceSource,
            @DefaultValue AuctionFees auctionFees) {
    }

    /**
     * Auction House fees for a BIN listing (to verify in game; Hypixel changes them).
     *
     * @param smallRate    listing fee below {@code mediumFrom}
     * @param mediumRate   listing fee from {@code mediumFrom}
     * @param largeRate    listing fee from {@code largeFrom}
     * @param mediumFrom   price where the medium rate starts
     * @param largeFrom    price where the large rate starts
     * @param claimTaxRate tax taken when claiming coins of a sale above {@code claimTaxFrom}
     * @param claimTaxFrom sale price above which the claim tax applies
     */
    public record AuctionFees(
            @DefaultValue("0.01") double smallRate,
            @DefaultValue("0.02") double mediumRate,
            @DefaultValue("0.025") double largeRate,
            @DefaultValue("10000000") double mediumFrom,
            @DefaultValue("100000000") double largeFrom,
            @DefaultValue("0.01") double claimTaxRate,
            @DefaultValue("1000000") double claimTaxFrom) {
    }

    /**
     * @param quickForgeReduction Quick Forge perk reduction as a fraction, as shown in the HOTM tree
     *                            (for example 0.2 for 20%); null if not set
     * @param moltenForge         Cole's Molten Forge perk (25%): AUTO reads the election resource
     * @param nextSessionIn       usual time until the player can refill a forge slot
     */
    public record Forge(
            Double quickForgeReduction,
            @DefaultValue("AUTO") Toggle moltenForge,
            @DefaultValue("8h") Duration nextSessionIn) {
    }

    /**
     * @param dailyLimit  items of one kind a player can buy from NPC shops per day
     * @param marketShare fraction of an item's daily traded volume one player can expect to buy or sell
     *                    without moving the price; caps the NPC Flipper's daily estimate
     */
    public record Npc(
            @DefaultValue("640") int dailyLimit,
            @DefaultValue("0.05") double marketShare) {
    }

    /**
     * @param marketShare fraction of the slower side's daily volume one player's orders can expect to fill
     * @param thinBook    fewer orders than this on either side of the book raise a THIN_BOOK alert
     */
    public record Bazaar(
            @DefaultValue("0.05") double marketShare,
            @DefaultValue("5") int thinBook) {
    }

    /**
     * @param minAuctionSales Auction House values from fewer clean sales than this in 7 days raise FEW_SALES
     * @param wideSpread      a Bazaar order whose instant price is this much worse (fraction) raises WIDE_SPREAD
     * @param manipulationRatio a Bazaar price above this multiple of its 7-day average raises MANIPULATION
     */
    public record Alerts(
            @DefaultValue("3") int minAuctionSales,
            @DefaultValue("0.5") double wideSpread,
            @DefaultValue("2") double manipulationRatio) {
    }

    /**
     * @param timeZone IANA time zone for clock times such as when a forge job ends ("Europe/Istanbul");
     *                 blank uses the computer's zone
     */
    public record Display(@DefaultValue("") String timeZone) {

        public java.time.ZoneId zone() {
            return timeZone == null || timeZone.isBlank() ? java.time.ZoneId.systemDefault()
                    : java.time.ZoneId.of(timeZone.trim());
        }
    }

    /** A setting that is detected automatically unless forced on or off. */
    public enum Toggle { AUTO, ON, OFF }

    /**
     * @param enabled               master switch for all collectors (off in tests)
     * @param bazaarInterval        Bazaar poll interval
     * @param auctionsEndedInterval ended auctions poll interval; keep at or below 60 s
     * @param auctionsInterval      full active-auction scan for tracked items (about 55 MB per scan)
     * @param resourcesInterval     items, collections and election refresh
     * @param retentionInterval     how often the retention job runs
     * @param trackedAuctionItems   extra item IDs (NEU names) whose lowest BIN is recorded
     */
    public record Collection(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("60s") Duration bazaarInterval,
            @DefaultValue("60s") Duration auctionsEndedInterval,
            @DefaultValue("30m") Duration auctionsInterval,
            @DefaultValue("24h") Duration resourcesInterval,
            @DefaultValue("1h") Duration retentionInterval,
            @DefaultValue List<String> trackedAuctionItems) {
    }

    /**
     * @param enabled         whether the NEU repository is downloaded
     * @param commitUrl       GitHub API URL returning the latest commit of the repository
     * @param archiveUrl      archive URL template; {@code {sha}} is replaced by the commit
     * @param refreshInterval how often to check for a new commit
     */
    public record Neu(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("https://api.github.com/repos/NotEnoughUpdates/NotEnoughUpdates-REPO/commits/master")
            String commitUrl,
            @DefaultValue("https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO/archive/{sha}.zip")
            String archiveUrl,
            @DefaultValue("24h") Duration refreshInterval) {
    }

    /**
     * @param fullResolution how long minute-level snapshots are kept
     * @param downsample     whether older data is kept as hourly summaries instead of deleted
     */
    public record Retention(
            @DefaultValue("7d") Duration fullResolution,
            @DefaultValue("true") boolean downsample) {
    }

    /**
     * Sale history backfill from Coflnet's public API (no account needed). Only item IDs are sent.
     *
     * @param enabled         whether sale history of Auction House items is fetched from Coflnet
     * @param baseUrl         Coflnet API base URL
     * @param interval        how often a backfill run starts
     * @param itemsPerRun     items fetched per run
     * @param requestInterval pause between requests (Coflnet allows about 100 a minute)
     * @param refetchAfter    an item is fetched again after this long
     * @param token           reserved for Coflnet account features; not needed for backfill
     */
    public record Coflnet(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("https://sky.coflnet.com") String baseUrl,
            @DefaultValue("5m") Duration interval,
            @DefaultValue("40") int itemsPerRun,
            @DefaultValue("3s") Duration requestInterval,
            @DefaultValue("24h") Duration refetchAfter,
            @DefaultValue("") String token) {
    }
}
