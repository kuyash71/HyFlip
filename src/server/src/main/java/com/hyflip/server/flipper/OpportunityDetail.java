package com.hyflip.server.flipper;

import com.hyflip.server.engine.CostSheet;
import com.hyflip.server.profile.Unlocks;
import java.util.Map;

/**
 * One opportunity with its full cost sheet.
 *
 * @param names display names of every item in the sheet, by ID
 */
public record OpportunityDetail(Opportunity summary, CostSheet sheet, Unlocks.Unlock unlock, Guide guide,
                                Map<String, String> names) {
}
