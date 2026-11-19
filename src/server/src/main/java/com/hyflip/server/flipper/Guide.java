package com.hyflip.server.flipper;

import java.util.List;

/**
 * What the player does by hand, and why it pays. The app never performs a step.
 *
 * @param steps ordered actions
 * @param logic short write-up of the reasoning and the risks
 */
public record Guide(List<String> steps, String logic) {
}
