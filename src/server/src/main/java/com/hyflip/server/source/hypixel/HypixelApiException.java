package com.hyflip.server.source.hypixel;

/** A Hypixel request failed or returned {@code success: false}. */
public class HypixelApiException extends RuntimeException {

    private final int status;

    public HypixelApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    /** HTTP status, or 0 when the request did not get a response. */
    public int status() {
        return status;
    }

    /** True for statuses worth retrying later (rate limit, server errors, no response). */
    public boolean retryable() {
        return status == 0 || status == 429 || status >= 500;
    }
}
