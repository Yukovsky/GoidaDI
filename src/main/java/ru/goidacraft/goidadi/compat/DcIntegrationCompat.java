package ru.goidacraft.goidadi.compat;

import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

import de.erdbeerbaerlp.dcintegration.common.DiscordIntegration;
import de.erdbeerbaerlp.dcintegration.common.storage.linking.LinkManager;
import de.erdbeerbaerlp.dcintegration.common.storage.linking.PlayerLink;
import de.erdbeerbaerlp.dcintegration.common.storage.linking.PlayerSettings;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;

/**
 * Null-safe, fail-soft wrappers over Discord Integration. Discord Integration is a soft dependency:
 * every method first checks {@link #isLoaded()} and wraps DI/JDA access in a {@code try/catch} so a
 * missing class never propagates. None of the DI/JDA classes referenced here appear in this class's
 * <em>signatures</em> (only in method bodies that do not run when DI is absent), so this class itself
 * always loads even on a server without DI.
 */
public final class DcIntegrationCompat {
    private static final Logger LOG = LoggerFactory.getLogger(DcIntegrationCompat.class);

    private static volatile Boolean loaded;

    private DcIntegrationCompat() {}

    public static boolean isLoaded() {
        Boolean l = loaded;
        if (l == null) {
            l = ModList.get() != null && ModList.get().isLoaded("dcintegration");
            loaded = l;
        }
        return l;
    }

    /** @return true when DI is present and its JDA bot is connected. */
    public static boolean isBotReady() {
        if (!isLoaded()) return false;
        try {
            return DiscordIntegration.INSTANCE != null && DiscordIntegration.INSTANCE.getJDA() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static JDA jda() {
        try {
            return DiscordIntegration.INSTANCE == null ? null : DiscordIntegration.INSTANCE.getJDA();
        } catch (Throwable t) {
            return null;
        }
    }

    /** @return the bot's Discord username (e.g. "GoidaBot"), or {@code null} when unavailable. */
    public static String getBotName() {
        JDA j = jda();
        if (j == null) return null;
        try {
            return j.getSelfUser().getName();
        } catch (Throwable t) {
            return null;
        }
    }

    /** @return the bot's numeric Discord user ID, or {@code null} when unavailable. */
    public static String getBotId() {
        JDA j = jda();
        if (j == null) return null;
        try {
            return j.getSelfUser().getId();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Mirrors a link into DI's LinkedPlayers.json. Best-effort; gated only by DI's enableLinking. */
    public static void mirrorLink(String discordId, UUID mcUuid) {
        if (!isLoaded()) return;
        try {
            if (LinkManager.isDiscordUserLinked(discordId) || LinkManager.isPlayerLinked(mcUuid)) return;
            PlayerLink link = new PlayerLink(discordId, mcUuid.toString(), "", new PlayerSettings());
            LinkManager.addLink(link);
        } catch (Throwable t) {
            LOG.debug("mirrorLink skipped: {}", t.toString());
        }
    }

    public static void mirrorUnlink(String discordId) {
        if (!isLoaded() || discordId == null) return;
        try {
            LinkManager.unlinkPlayer(discordId);
        } catch (Throwable t) {
            LOG.debug("mirrorUnlink skipped: {}", t.toString());
        }
    }

    /** Resolves a Discord user's display name from its ID (blocking; call off the server thread). */
    public static String resolveDiscordName(String discordId) {
        JDA j = jda();
        if (j == null || discordId == null) return null;
        try {
            var user = j.retrieveUserById(discordId).complete();
            return user == null ? null : user.getName();
        } catch (Throwable t) {
            LOG.debug("resolveDiscordName failed for {}: {}", discordId, t.toString());
            return null;
        }
    }

    public static void grantRole(String discordId, String roleId) {
        roleOp(discordId, roleId, true);
    }

    public static void removeRole(String discordId, String roleId) {
        roleOp(discordId, roleId, false);
    }

    private static void roleOp(String discordId, String roleId, boolean add) {
        if (roleId == null || roleId.isBlank() || discordId == null) return;
        JDA j = jda();
        if (j == null) return;
        try {
            for (Guild guild : j.getGuilds()) {
                Role role = guild.getRoleById(roleId);
                if (role == null) continue;
                guild.retrieveMemberById(discordId).queue(member -> {
                    try {
                        if (add) guild.addRoleToMember(member, role).queue();
                        else guild.removeRoleFromMember(member, role).queue();
                    } catch (Throwable ignored) {
                    }
                }, err -> { /* member not in guild — ignore */ });
            }
        } catch (Throwable t) {
            LOG.debug("roleOp failed: {}", t.toString());
        }
    }

    /** Sends a message to the configured admin log channel. Best-effort. */
    public static void sendAdminLog(String channelId, String message) {
        if (channelId == null || channelId.isBlank()) return;
        JDA j = jda();
        if (j == null) return;
        try {
            MessageChannel ch = j.getChannelById(MessageChannel.class, channelId);
            if (ch != null) ch.sendMessage(message).queue();
        } catch (Throwable t) {
            LOG.debug("sendAdminLog failed: {}", t.toString());
        }
    }
}
