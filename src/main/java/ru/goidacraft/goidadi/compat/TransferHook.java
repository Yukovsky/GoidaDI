package ru.goidacraft.goidadi.compat;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.goidacraft.goidadi.link.LinkService;

import java.util.UUID;

import ru.goidacraft.goidaauth.transfer.AccountTransfer;

/**
 * Extends GoidaAuth's {@code /transferaccount} so the Discord link travels with the account. Because
 * we register on GoidaAuth's own {@code AccountTransfer.PostTransferHook} registry, the hook fires
 * <em>only</em> for GoidaAuth's transfer command — a {@code transferaccount} added by another mod
 * never triggers it, and if GoidaAuth is absent there is no command and no hook at all.
 *
 * <p>Loaded only when GoidaAuth is present (callers guard with {@link GoidaAuthHook#isAvailable()}).
 */
public final class TransferHook {
    private static final Logger LOG = LoggerFactory.getLogger(TransferHook.class);

    private TransferHook() {}

    public static void register(MinecraftServer server, LinkService linkService) {
        try {
            AccountTransfer.addPostTransferHook((fromUuid, toUuid, deletedSource) -> {
                String toName = resolveName(server, toUuid);
                linkService.transferLink(fromUuid, toUuid, toName, deletedSource)
                        .exceptionally(ex -> {
                            LOG.error("Discord link transfer {} -> {} failed", fromUuid, toUuid, ex);
                            return null;
                        });
            });
            ru.goidacraft.goidaauth.GoidaAuthApi.addUuidChangedHook((oldUuid, newUuid, username) -> {
                linkService.transferLink(oldUuid, newUuid, username, true)
                        .exceptionally(ex -> {
                            LOG.error("Discord link UUID-change {} -> {} failed", oldUuid, newUuid, ex);
                            return null;
                        });
            });
            LOG.info("Hooked into GoidaAuth account transfer and UUID changes.");
        } catch (Throwable t) {
            LOG.error("Failed to hook GoidaAuth transfer", t);
        }
    }

    private static String resolveName(MinecraftServer server, UUID uuid) {
        ServerPlayer p = server.getPlayerList().getPlayer(uuid);
        if (p != null) return p.getGameProfile().getName();
        try {
            return server.getProfileCache() == null ? null
                    : server.getProfileCache().get(uuid).map(GameProfile::getName).orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
