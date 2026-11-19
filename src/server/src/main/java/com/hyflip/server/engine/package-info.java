/**
 * Shared calculation engine used by every flipper: ingredient cost, output value, Bazaar and
 * Auction House taxes, liquidity, profit metrics and ranking. Pure functions over prices and the
 * user profile, so it can be unit-tested without a database.
 */
package com.hyflip.server.engine;
