package ru.goidacraft.goidadi.compat;

import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.goidacraft.goidadi.deadline.DeadlineTracker;
import ru.goidacraft.goidadi.gate.DcGateManager;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import ru.goidacraft.goidaauth.GoidaAuth;
import ru.goidacraft.goidaauth.GoidaAuthApi;
import ru.goidacraft.goidaauth.database.UserRecord;

/**
 * Soft integration with GoidaAuth. All GoidaAuth/GoidaAuthApi references live only inside method
 * bodies (never in signatures), so this class still loads on a server without GoidaAuth; callers
 * always guard with {@link #isAvailable()} before invoking anything that touches GoidaAuth.
 *
 * <p>Registers the "player authorized" listener (the precise "may now play" signal that starts the
 * grace window) and an {@link GoidaAuthApi.InteractionBlocker} so GoidaAuth's packet-level inventory
 * guard also blocks GoidaDI-gated players. Also resolves the authoritative UUID for a name.
 */
public final class GoidaAuthHook {
    private static final Logger LOG = LoggerFactory.getLogger(GoidaAuthHook.class);

    private GoidaAuthHook() {}

    public static boolean isAvailable() {
        try {
            return ModList.get() != null && ModList.get().isLoaded("goidaauth") && GoidaAuth.get() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void register(DeadlineTracker tracker, DcGateManager gate) {
        try {
            GoidaAuthApi.addAuthorizedListener((player, premium, wasRegistered) -> tracker.onAuthorized(player));
            GoidaAuthApi.addInteractionBlocker(player -> gate.isGated(player.getUUID()));
            LOG.info("Hooked into GoidaAuth (authorized + inventory-block).");
        } catch (Throwable t) {
            LOG.error("Failed to hook GoidaAuth", t);
        }
    }

    /** Resolves the authoritative UUID GoidaAuth stores for a name (offline or premium). */
    public static CompletableFuture<Optional<UUID>> resolveUuid(String name) {
        if (!isAvailable()) return CompletableFuture.completedFuture(Optional.empty());
        try {
            return GoidaAuth.get().database().findByName(name)
                    .thenApply(opt -> opt.map(UserRecord::uuid));
        } catch (Throwable t) {
            LOG.debug("resolveUuid failed: {}", t.toString());
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }
}
