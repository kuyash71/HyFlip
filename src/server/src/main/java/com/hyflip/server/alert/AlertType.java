package com.hyflip.server.alert;

/** Kinds of alert, computed with the opportunities. */
public enum AlertType {
    /** An input or the output has no price at all; the opportunity is incomplete. */
    MISSING_PRICE,
    /** The price is the median of recent sales: no listing, or the lowest BIN is above what sells. */
    ESTIMATED_PRICE,
    /** The price was observed long ago (collector not running, or item rarely traded). */
    STALE_PRICE,
    /**
     * The Auction House value rests on fewer clean sales than {@code alerts.min-auction-sales} in 7
     * days: a single (often joke) listing can set it.
     */
    FEW_SALES,
    /**
     * A Bazaar order sits far from the instant price: a buy order there fills slowly, and buying
     * instantly instead costs much more (or a sell offer, and selling instantly pays much less).
     */
    WIDE_SPREAD,
    /**
     * A Bazaar price stands far above its 7-day average ({@code alerts.manipulation-ratio}): the
     * item may be manipulated, and the price may fall back before the flip completes.
     */
    MANIPULATION,
    /** A Bazaar price moved sharply in the last minutes (a dump or a spike), so it may not hold. */
    RECENT_CHANGE,
    /** Few orders on one side of the Bazaar book, so a single player can set (or pull) the price. */
    THIN_BOOK,
    /** Daily volume is low compared with what one run produces. */
    LOW_VOLUME,
    /**
     * Buying the inputs takes more items than one inventory holds (36 slots of 64): the flip means
     * many rounds of buying and crafting, usually far more than one session allows.
     */
    BULK_CRAFT,
    /** A requirement cannot be checked because the profile lacks the value. */
    UNLOCK_UNKNOWN,
    /** A forge slot is done. */
    FORGE_FINISHED
}
