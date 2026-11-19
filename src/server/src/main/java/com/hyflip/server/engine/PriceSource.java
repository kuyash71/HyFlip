package com.hyflip.server.engine;

/**
 * How Bazaar prices are taken. {@code INSTANT} uses insta-buy and insta-sell prices; {@code ORDER}
 * assumes a buy order near the highest one and a sell offer near the lowest one.
 */
public enum PriceSource { INSTANT, ORDER }
