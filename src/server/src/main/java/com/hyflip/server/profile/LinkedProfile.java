package com.hyflip.server.profile;

import java.util.List;
import java.util.Map;

/**
 * What profile linking read from the SkyBlock profile API.
 *
 * @param username            Minecraft username the profile was read for
 * @param uuid                the member's UUID (no dashes)
 * @param profileId           SkyBlock profile ID
 * @param cuteName            profile name such as "Apple"
 * @param profileNames        every profile of the player, for choosing another one
 * @param hotmLevel           HOTM level from the mining experience, null if the member never mined
 * @param hotmExperience      HOTM experience
 * @param quickForgeLevel     Quick Forge perk level, null if not unlocked
 * @param quickForgeReduction forge time reduction of that level (0 if the perk is toggled off)
 * @param collections         highest unlocked tier per collection ID
 * @param forge               jobs in the forge, empty if none
 * @param forgeKnown          false if the profile has no forge data at all
 * @param fetchedAt           when the profile was read
 * @param purse               coins in the member's purse, null if the API did not say
 * @param bank                coins in the profile's bank, null if the bank API is off or absent
 * @param slayers             slayer level by boss (zombie, spider, ...), null for profiles stored before it was read
 */
public record LinkedProfile(String username, String uuid, String profileId, String cuteName, List<String> profileNames,
                            Integer hotmLevel, double hotmExperience, Integer quickForgeLevel,
                            Double quickForgeReduction, Map<String, Integer> collections, List<ForgeProcess> forge,
                            boolean forgeKnown, long fetchedAt, Double purse, Double bank, Map<String, Integer> slayers) {

    /** Without slayer levels (profiles stored before they were read, tests). */
    public LinkedProfile(String username, String uuid, String profileId, String cuteName, List<String> profileNames,
                         Integer hotmLevel, double hotmExperience, Integer quickForgeLevel, Double quickForgeReduction,
                         Map<String, Integer> collections, List<ForgeProcess> forge, boolean forgeKnown,
                         long fetchedAt, Double purse, Double bank) {
        this(username, uuid, profileId, cuteName, profileNames, hotmLevel, hotmExperience, quickForgeLevel,
                quickForgeReduction, collections, forge, forgeKnown, fetchedAt, purse, bank, null);
    }

    /** Without coin balances (profiles stored before they were read, tests). */
    public LinkedProfile(String username, String uuid, String profileId, String cuteName, List<String> profileNames,
                         Integer hotmLevel, double hotmExperience, Integer quickForgeLevel, Double quickForgeReduction,
                         Map<String, Integer> collections, List<ForgeProcess> forge, boolean forgeKnown,
                         long fetchedAt) {
        this(username, uuid, profileId, cuteName, profileNames, hotmLevel, hotmExperience, quickForgeLevel,
                quickForgeReduction, collections, forge, forgeKnown, fetchedAt, null, null, null);
    }

    /** A job in the forge: slot (1-based), output item and start time. */
    public record ForgeProcess(int slot, String itemId, long startedAt) {
    }

    /** Highest slot with a job, 0 if the forge is empty. */
    public int highestForgeSlot() {
        return forge.stream().mapToInt(ForgeProcess::slot).max().orElse(0);
    }
}
