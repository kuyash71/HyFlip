package com.hyflip.server.profile;

/** Profile linking failed; {@link Kind} picks the HTTP status. */
public class ProfileLinkException extends RuntimeException {

    public enum Kind {
        /** Username or API key missing. */
        NOT_CONFIGURED,
        /** Unknown username or profile name. */
        NOT_FOUND,
        /** Hypixel refused the API key. */
        KEY_REJECTED,
        RATE_LIMITED,
        UNAVAILABLE
    }

    private final Kind kind;

    public ProfileLinkException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public boolean retryable() {
        return kind == Kind.RATE_LIMITED || kind == Kind.UNAVAILABLE;
    }
}
