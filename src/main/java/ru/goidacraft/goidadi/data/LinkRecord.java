package ru.goidacraft.goidadi.data;

import java.time.Instant;
import java.util.UUID;

/**
 * A single Minecraft &harr; Discord link row, kept in GoidaDI's own H2 database.
 *
 * <p>{@code discordId}/{@code discordName}/{@code linkedAt} are {@code null} until the player links.
 * {@code firstSeen}/{@code deadline} track the mandatory-link grace window (the player must link
 * before {@code deadline}). The row exists from the player's first full join after the mod was
 * installed, even when they have not linked yet.
 */
public record LinkRecord(
        UUID mcUuid,
        String mcName,
        String discordId,    // null = not linked yet
        String discordName,  // Discord tag/name for human-friendly viewing
        Instant firstSeen,
        Instant deadline,
        Instant linkedAt     // null when not linked
) {
    public boolean isLinked() {
        return discordId != null && !discordId.isBlank();
    }

    public boolean isExpired(Instant now) {
        return !isLinked() && now.isAfter(deadline);
    }
}
