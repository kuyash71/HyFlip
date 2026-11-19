package com.hyflip.server.flipper;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyflip.server.alert.AlertType;
import com.hyflip.server.engine.CostSheet;
import com.hyflip.server.engine.Plan;
import com.hyflip.server.engine.Quote;
import com.hyflip.server.engine.Venue;
import java.util.List;
import org.junit.jupiter.api.Test;

class BulkCraftTest {

    static Quote quote(String id, double quantity) {
        return new Quote(id, quantity, Venue.BAZAAR, 1, quantity, 0, 0L, false);
    }

    /** {@code blocks} enchanted blocks, each crafted from 160 enchanted items, each from 160 base items. */
    static CostSheet sheet(int blocks) {
        double enchanted = blocks * 160.0;
        double base = enchanted * 160;
        Plan baseBuy = Plan.buy(quote("BASE", base));
        Plan enchantedCraft = new Plan("ENCHANTED", enchanted, Plan.Method.CRAFT, base, null, enchanted, 0,
                List.of(baseBuy));
        Plan block = new Plan("BLOCK", blocks, Plan.Method.CRAFT, base, null, blocks, 0, List.of(enchantedCraft));
        return CostSheet.of(List.of(new CostSheet.Line("BLOCK", blocks, null, block)), 0, "OUT",
                quote("OUT", 1));
    }

    @Test
    void flagsInputsThatDoNotFitInOneInventory() {
        CostSheet sheet = sheet(7);
        var alert = FlipSupport.bulkCraft(sheet, List.copyOf(sheet.inputs().get(0).plan().purchases().values()));
        assertThat(alert).isPresent();
        assertThat(alert.get().type()).isEqualTo(AlertType.BULK_CRAFT);
        // 7 × 160 × 160 = 179,200 base items: 78 inventories, 7 + 1,120 crafts
        assertThat(alert.get().message()).isEqualTo("Needs about 179,200 bought items (78 full inventories) and 1,127 crafts per run");
    }

    @Test
    void oneInventoryOrLessIsFine() {
        Plan small = Plan.buy(quote("BASE", FlipSupport.INVENTORY_ITEMS));
        CostSheet sheet = CostSheet.of(List.of(new CostSheet.Line("BASE", FlipSupport.INVENTORY_ITEMS, null, small)), 0,
                "OUT", quote("OUT", 1));
        assertThat(FlipSupport.bulkCraft(sheet, List.of(small.quote()))).isEmpty();
    }
}
