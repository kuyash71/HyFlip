package com.hyflip.server.source.mojang;

import java.util.Optional;

/** Resolves a Minecraft username to its UUID (32 hex digits, no dashes). */
public interface MojangApi {

    /** Empty if no player has this name. */
    Optional<String> uuid(String username);
}
