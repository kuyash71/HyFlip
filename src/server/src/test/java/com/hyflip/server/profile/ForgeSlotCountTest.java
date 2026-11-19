package com.hyflip.server.profile;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ForgeSlotCountTest {

    @Test
    void collectingTheLastSlotsDoesNotShrinkTheForge() {
        // four slots were busy earlier today; now only slot 2 holds a job
        assertThat(PlayerContextService.forgeSlots(2, 4)).isEqualTo(4);
        assertThat(PlayerContextService.forgeSlots(0, 4)).isEqualTo(4);
        assertThat(PlayerContextService.forgeSlots(5, 4)).isEqualTo(5);
    }

    @Test
    void startsAtTheDefaultAndStaysInRange() {
        assertThat(PlayerContextService.forgeSlots(0, 0)).isEqualTo(PlayerContext.DEFAULT_FORGE_SLOTS);
        assertThat(PlayerContextService.forgeSlots(1, 0)).isEqualTo(PlayerContext.DEFAULT_FORGE_SLOTS);
        assertThat(PlayerContextService.forgeSlots(0, 12)).isEqualTo(PlayerContextService.MAX_FORGE_SLOTS);
    }
}
