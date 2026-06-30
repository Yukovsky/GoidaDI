package ru.goidacraft.goidadi.compat;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Resolves a Minecraft name to the authoritative UUID. Prefers an online player's join UUID, then
 * GoidaAuth's stored UUID (correct for both offline and premium accounts), and only falls back to
 * the deterministic offline UUID when GoidaAuth is unavailable or has no record.
 */
public final class NameResolver {
    private NameResolver() {}

    public static CompletableFuture<UUID> resolve(MinecraftServer server, String name) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) return CompletableFuture.completedFuture(online.getUUID());
        if (GoidaAuthHook.isAvailable()) {
            return GoidaAuthHook.resolveUuid(name).thenApply(opt -> opt.orElseGet(() -> offline(name)));
        }
        return CompletableFuture.completedFuture(offline(name));
    }

    public static UUID offline(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
