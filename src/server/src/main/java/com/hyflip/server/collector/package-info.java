/**
 * Scheduled pollers that pull data from the sources in {@code source.*} and write snapshots
 * through {@code storage}. Also detects offline gaps on start-up and triggers Coflnet backfill
 * when a token is configured. Collectors only read external data; they never act in the game.
 */
package com.hyflip.server.collector;
