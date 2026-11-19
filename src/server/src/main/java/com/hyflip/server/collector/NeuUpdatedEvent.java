package com.hyflip.server.collector;

/** Published after a new NEU repository commit was loaded. */
public record NeuUpdatedEvent(String commit) {
}
