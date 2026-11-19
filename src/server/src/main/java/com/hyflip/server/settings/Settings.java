package com.hyflip.server.settings;

import com.hyflip.server.config.HyFlipProperties;

/**
 * Settings editable in the app, with the same shape as the matching {@code config.yaml} sections.
 *
 * @param player  identity and manual personal context
 * @param trading budget, taxes, fees and price source
 * @param forge   forge speed and session
 * @param npc     NPC Flipper limits
 * @param bazaar  Bazaar Flipper fill estimate
 * @param alerts  data-quality alert thresholds
 * @param display time zone for clock times
 */
public record Settings(HyFlipProperties.Player player, HyFlipProperties.Trading trading,
                       HyFlipProperties.Forge forge, HyFlipProperties.Npc npc, HyFlipProperties.Bazaar bazaar,
                       HyFlipProperties.Alerts alerts, HyFlipProperties.Display display) {

    public static Settings of(HyFlipProperties p) {
        return new Settings(p.player(), p.trading(), p.forge(), p.npc(), p.bazaar(), p.alerts(), p.display());
    }
}
