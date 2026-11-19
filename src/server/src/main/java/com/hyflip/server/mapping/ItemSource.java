package com.hyflip.server.mapping;

/** Where a source-specific ID comes from; stored in {@code item_alias.source}. */
public enum ItemSource {
    /** Hypixel items resource ID. */
    HYPIXEL_ITEM,
    /** Bazaar product ID. */
    BAZAAR,
    /** Item decoded from an auction, keyed by its canonical ID. */
    AUCTION,
    /** NEU internal name. */
    NEU
}
