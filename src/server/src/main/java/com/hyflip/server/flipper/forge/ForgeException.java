package com.hyflip.server.flipper.forge;

/** A rejected Forge Tracker or calibration request; the API maps {@link Kind} to a status code. */
public class ForgeException extends RuntimeException {

    public enum Kind { INVALID, NOT_FOUND, CONFLICT }

    private final Kind kind;

    public ForgeException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    static ForgeException invalid(String message) {
        return new ForgeException(Kind.INVALID, message);
    }

    static ForgeException notFound(String message) {
        return new ForgeException(Kind.NOT_FOUND, message);
    }

    static ForgeException conflict(String message) {
        return new ForgeException(Kind.CONFLICT, message);
    }
}
