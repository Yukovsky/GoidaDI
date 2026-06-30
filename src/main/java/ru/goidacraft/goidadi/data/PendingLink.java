package ru.goidacraft.goidadi.data;

import java.time.Instant;
import java.util.UUID;

/**
 * A short-lived link code a player must DM to the Discord bot to confirm ownership of their Discord
 * account. Persisted in H2 so it survives reconnects; expired entries are purged on access and on a
 * periodic sweep (see {@link LinkDatabase}).
 *
 * @param override {@code true} when the player already has a link and is re-pointing it at a new
 *                 Discord account (override flow); the confirmation then replaces the old link.
 */
public record PendingLink(
        int code,
        UUID mcUuid,
        String mcName,
        Instant createdAt,
        boolean override
) {}
