package com.hyflip.server.alert;

/**
 * A warning on an opportunity or forge slot. Alerts never remove an opportunity.
 *
 * @param type    kind of alert
 * @param message reason the UI shows
 * @param itemId  item it is about, null if it concerns the whole opportunity
 */
public record Alert(AlertType type, String message, String itemId) {
}
