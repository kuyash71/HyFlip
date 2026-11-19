package com.hyflip.server.source.coflnet;

/** A failed Coflnet request. */
public class CoflnetException extends RuntimeException {

    private final int status;

    public CoflnetException(int status, String message) {
        super(message);
        this.status = status;
    }

    /** HTTP status, 0 if the request did not get a response. */
    public int status() {
        return status;
    }

    public boolean rateLimited() {
        return status == 429;
    }
}
