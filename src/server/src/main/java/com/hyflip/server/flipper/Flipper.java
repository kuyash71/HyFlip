package com.hyflip.server.flipper;

import java.util.Optional;

/** A flipper: turns recipes or offers into ranked opportunities through the engine. */
public interface Flipper {

    FlipType type();

    FlipList list(FlipQuery query);

    Optional<OpportunityDetail> detail(String id, FlipQuery query);
}
