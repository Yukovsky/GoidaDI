package ru.goidacraft.goidadi.link;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.goidacraft.goidadi.Config;
import ru.goidacraft.goidadi.compat.DcIntegrationCompat;
import ru.goidacraft.goidadi.data.LinkDatabase;
import ru.goidacraft.goidadi.data.LinkRecord;
import ru.goidacraft.goidadi.data.PendingLink;
import ru.goidacraft.goidadi.gate.DcGateManager;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Business logic for the "our own link on DI's bot" flow: generating codes, confirming a code that
 * arrived as a Discord DM, manual admin links, overrides and unlinks. Every compound check-then-write
 * runs inside a single {@link LinkDatabase#compute} task, so there is no TOCTOU race even with
 * concurrent joins/links. Side effects that touch a player or effects are always re-dispatched to the
 * server thread.
 */
public final class LinkService {
    private static final Logger LOG = LoggerFactory.getLogger(LinkService.class);
    private static final SecureRandom RNG = new SecureRandom();

    public enum Status { LINKED, BAD_CODE, EXPIRED_CODE, DISCORD_TAKEN }

    public record ConfirmResult(Status status, UUID mcUuid, String mcName, String discordId,
                                String discordName, boolean override) {}

    private final LinkDatabase db;
    private final DcGateManager gate;
    private volatile MinecraftServer server;

    public LinkService(LinkDatabase db, DcGateManager gate) {
        this.db = db;
        this.gate = gate;
    }

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    // ------------------------------------------------------------------
    // /dclink — generate a code (or start override flow if already linked)
    // ------------------------------------------------------------------

    public void startLink(ServerPlayer player) {
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().getName();
        db.compute(c -> {
            Optional<LinkRecord> rec = LinkDatabase.findByUuidSync(c, uuid);
            boolean override = rec.isPresent() && rec.get().isLinked();
            int code = freshCode(c);
            Instant firstSeen = rec.map(LinkRecord::firstSeen).orElse(Instant.now());
            Instant deadline = rec.map(LinkRecord::deadline)
                    .orElse(Instant.now().plus(Config.GRACE_DAYS.get(), ChronoUnit.DAYS));
            LinkDatabase.ensureRowSync(c, uuid, name, firstSeen, deadline);
            LinkDatabase.deletePendingByUuidSync(c, uuid);
            LinkDatabase.savePendingSync(c, new PendingLink(code, uuid, name, Instant.now(), override));
            return new int[]{code, override ? 1 : 0, rec.map(r -> r.isLinked() ? 1 : 0).orElse(0)};
        }).thenAccept(res -> onServer(() -> {
            ServerPlayer p = online(uuid);
            if (p == null) return;
            int code = res[0];
            boolean override = res[1] == 1;
            int ttlMin = Config.CODE_TTL_MIN.get();

            if (override) {
                sendCode(p, Config.MSG_OVERRIDE_PROMPT.get(), code);
            } else {
                sendCode(p, Config.MSG_LINK_CODE.get(), code);
            }
            send(p, String.format(Config.MSG_LINK_TTL.get(), ttlMin));
            sendBotLine(p);
        })).exceptionally(logErr("startLink"));
    }

    /** Generates a pending code without messaging the player; used by the kick (3C) fallback. */
    public CompletableFuture<Integer> generateKickCode(UUID uuid, String name) {
        return db.compute(c -> {
            Optional<LinkRecord> rec = LinkDatabase.findByUuidSync(c, uuid);
            int code = freshCode(c);
            Instant firstSeen = rec.map(LinkRecord::firstSeen).orElse(Instant.now());
            Instant deadline = rec.map(LinkRecord::deadline)
                    .orElse(Instant.now().plus(Config.GRACE_DAYS.get(), ChronoUnit.DAYS));
            LinkDatabase.ensureRowSync(c, uuid, name, firstSeen, deadline);
            LinkDatabase.deletePendingByUuidSync(c, uuid);
            LinkDatabase.savePendingSync(c, new PendingLink(code, uuid, name, Instant.now(), false));
            return code;
        });
    }

    private int freshCode(java.sql.Connection c) throws java.sql.SQLException {
        int digits = Config.CODE_LENGTH_DIGITS.get();
        int min = (int) Math.pow(10, digits - 1);
        int bound = (int) Math.pow(10, digits) - min;
        for (int attempt = 0; attempt < 64; attempt++) {
            int code = min + RNG.nextInt(bound);
            if (!LinkDatabase.codeExistsSync(c, code)) return code;
        }
        // extremely unlikely; fall back to time-based
        return min + (int) (System.nanoTime() % bound);
    }

    // ------------------------------------------------------------------
    // Confirmation from a Discord DM (called by DcBotBridge, off the server thread)
    // ------------------------------------------------------------------

    public CompletableFuture<ConfirmResult> confirmFromDiscord(int code, String discordId, String discordName) {
        return db.compute(c -> {
            Optional<PendingLink> pendingOpt = LinkDatabase.findPendingByCodeSync(c, code);
            if (pendingOpt.isEmpty()) return new ConfirmResult(Status.BAD_CODE, null, null, null, null, false);
            PendingLink pending = pendingOpt.get();

            Instant cutoff = Instant.now().minus(Config.CODE_TTL_MIN.get(), ChronoUnit.MINUTES);
            if (pending.createdAt().isBefore(cutoff)) {
                LinkDatabase.deletePendingByCodeSync(c, code);
                return new ConfirmResult(Status.EXPIRED_CODE, pending.mcUuid(), pending.mcName(), null, null, false);
            }

            // Is this Discord already linked to a *different* MC account?
            Optional<LinkRecord> byDiscord = LinkDatabase.findByDiscordIdSync(c, discordId);
            if (byDiscord.isPresent() && !byDiscord.get().mcUuid().equals(pending.mcUuid())) {
                return new ConfirmResult(Status.DISCORD_TAKEN, pending.mcUuid(), pending.mcName(), discordId,
                        discordName, pending.override());
            }

            Optional<LinkRecord> rec = LinkDatabase.findByUuidSync(c, pending.mcUuid());
            Instant firstSeen = rec.map(LinkRecord::firstSeen).orElse(Instant.now());
            Instant deadline = rec.map(LinkRecord::deadline)
                    .orElse(Instant.now().plus(Config.GRACE_DAYS.get(), ChronoUnit.DAYS));
            LinkDatabase.setLinkSync(c, pending.mcUuid(), pending.mcName(), discordId, discordName,
                    firstSeen, deadline);
            LinkDatabase.deletePendingByUuidSync(c, pending.mcUuid());
            return new ConfirmResult(Status.LINKED, pending.mcUuid(), pending.mcName(), discordId,
                    discordName, pending.override());
        });
    }

    /** Side effects after a successful confirmation: in-game release/message + role/mirror/notify. */
    public void afterLinked(ConfirmResult r) {
        if (r.status() != Status.LINKED) return;
        // off-thread side effects
        if (Config.MIRROR_TO_DC_INTEGRATION.get()) DcIntegrationCompat.mirrorLink(r.discordId(), r.mcUuid());
        DcIntegrationCompat.grantRole(r.discordId(), Config.VERIFIED_ROLE_ID.get());
        DcIntegrationCompat.sendAdminLog(Config.ADMIN_LOG_CHANNEL_ID.get(),
                String.format(Config.DC_NOTIFY_LINKED.get(), r.mcName(),
                        r.discordName() == null ? r.discordId() : r.discordName()));
        // in-game release + message
        onServer(() -> {
            gate.release(r.mcUuid());
            ServerPlayer p = online(r.mcUuid());
            if (p != null) {
                send(p, String.format(Config.MSG_LINK_SUCCESS.get(),
                        r.discordName() == null ? r.discordId() : r.discordName()));
            }
        });
    }

    // ------------------------------------------------------------------
    // Admin: create/override without confirmation
    // ------------------------------------------------------------------

    /** Admin link/override with no confirmation. Steals the Discord from another MC if needed. */
    public CompletableFuture<Boolean> adminSetLink(UUID mcUuid, String mcName, String discordId, String discordName) {
        return db.compute(c -> {
            // free the Discord from any other MC account first (admin override is authoritative)
            Optional<LinkRecord> byDiscord = LinkDatabase.findByDiscordIdSync(c, discordId);
            if (byDiscord.isPresent() && !byDiscord.get().mcUuid().equals(mcUuid)) {
                LinkDatabase.clearLinkSync(c, byDiscord.get().mcUuid());
            }
            Optional<LinkRecord> rec = LinkDatabase.findByUuidSync(c, mcUuid);
            Instant firstSeen = rec.map(LinkRecord::firstSeen).orElse(Instant.now());
            Instant deadline = rec.map(LinkRecord::deadline)
                    .orElse(Instant.now().plus(Config.GRACE_DAYS.get(), ChronoUnit.DAYS));
            LinkDatabase.setLinkSync(c, mcUuid, mcName, discordId, discordName, firstSeen, deadline);
            LinkDatabase.deletePendingByUuidSync(c, mcUuid);
            return true;
        }).thenApply(ok -> {
            if (ok) {
                if (Config.MIRROR_TO_DC_INTEGRATION.get()) DcIntegrationCompat.mirrorLink(discordId, mcUuid);
                DcIntegrationCompat.grantRole(discordId, Config.VERIFIED_ROLE_ID.get());
                // Объявляем привязку в канал так же, как при самостоятельной привязке игрока,
                // чтобы в канале были ВСЕ события привязки (в т.ч. созданные админом).
                DcIntegrationCompat.sendAdminLog(Config.ADMIN_LOG_CHANNEL_ID.get(),
                        String.format(Config.DC_NOTIFY_LINKED.get(), mcName,
                                discordName == null ? discordId : discordName));
                onServer(() -> {
                    gate.release(mcUuid);
                    ServerPlayer p = online(mcUuid);
                    if (p != null) send(p, String.format(Config.MSG_LINK_SUCCESS.get(),
                            discordName == null ? discordId : discordName));
                });
            }
            return ok;
        });
    }

    // ------------------------------------------------------------------
    // Unlink
    // ------------------------------------------------------------------

    public CompletableFuture<Boolean> unlink(UUID mcUuid) {
        // capture the old discord id first so we can drop the role / mirror
        return db.compute(c -> {
            Optional<LinkRecord> rec = LinkDatabase.findByUuidSync(c, mcUuid);
            String discordId = rec.map(LinkRecord::discordId).orElse(null);
            boolean changed = LinkDatabase.clearLinkSync(c, mcUuid);
            return new Object[]{changed, discordId};
        }).thenApply(arr -> {
            boolean changed = (Boolean) arr[0];
            String discordId = (String) arr[1];
            if (changed && discordId != null) {
                DcIntegrationCompat.removeRole(discordId, Config.VERIFIED_ROLE_ID.get());
                if (Config.MIRROR_TO_DC_INTEGRATION.get()) DcIntegrationCompat.mirrorUnlink(discordId);
            }
            // a now-unlinked player past their deadline must be re-gated
            onServer(() -> {
                ServerPlayer p = online(mcUuid);
                if (p != null) gate.reevaluate(p);
            });
            return changed;
        });
    }

    // ------------------------------------------------------------------
    // Transfer (called by TransferHook)
    // ------------------------------------------------------------------

    public CompletableFuture<Boolean> transferLink(UUID fromUuid, UUID toUuid, String toName, boolean deleteSource) {
        return db.compute(c -> {
            Optional<LinkRecord> from = LinkDatabase.findByUuidSync(c, fromUuid);
            if (from.isEmpty()) return false;
            LinkRecord r = from.get();
            // resolve a non-null display name for the target row
            Optional<LinkRecord> toExisting = LinkDatabase.findByUuidSync(c, toUuid);
            String name = toName != null ? toName
                    : toExisting.map(LinkRecord::mcName).orElse(r.mcName());
            // move the discord side onto the target, preserving the source's timing window
            if (r.isLinked()) {
                // clear the source first to avoid the unique discord_id collision
                LinkDatabase.clearLinkSync(c, fromUuid);
                LinkDatabase.setLinkSync(c, toUuid, name, r.discordId(), r.discordName(),
                        r.firstSeen(), r.deadline());
            } else {
                LinkDatabase.ensureRowSync(c, toUuid, name, r.firstSeen(), r.deadline());
                LinkDatabase.resetDeadlineSync(c, toUuid, r.firstSeen(), r.deadline());
            }
            if (deleteSource) {
                LinkDatabase.deleteByUuidSync(c, fromUuid);
            } else {
                // keep source row but drop its discord & obligation
                LinkDatabase.clearLinkSync(c, fromUuid);
            }
            return true;
        }).thenApply(ok -> {
            onServer(() -> {
                ServerPlayer p = online(toUuid);
                if (p != null) gate.reevaluate(p);
            });
            return ok;
        });
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private ServerPlayer online(UUID uuid) {
        MinecraftServer s = server;
        return s == null ? null : s.getPlayerList().getPlayer(uuid);
    }

    private void onServer(Runnable r) {
        MinecraftServer s = server;
        if (s != null) s.execute(r);
    }

    public static void send(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal(text));
    }

    /**
     * Sends a message that contains a link code formatted as a clickable "copy to clipboard" element.
     * The {@code template} must have exactly one {@code %d} placeholder for the code. Everything
     * before the placeholder is plain prefix text; everything after is plain suffix. The code itself
     * is rendered bold-white with a click-to-copy action and a tooltip.
     */
    private static void sendCode(ServerPlayer player, String template, int code) {
        String codeStr = String.valueOf(code);
        int pct = template.indexOf("%d");
        if (pct < 0) {
            // fallback: no placeholder, send formatted
            player.sendSystemMessage(Component.literal(String.format(template, code)));
            return;
        }
        String prefix = template.substring(0, pct);
        String suffix = template.substring(pct + 2);

        var codeComponent = Component.literal(codeStr)
                .withStyle(s -> s
                        .withColor(ChatFormatting.WHITE)
                        .withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, codeStr))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("§7Нажмите, чтобы скопировать код"))));

        var msg = Component.literal(prefix).append(codeComponent);
        if (!suffix.isEmpty()) msg = msg.append(Component.literal(suffix));
        player.sendSystemMessage(msg);
    }

    /**
     * Shows the bot name/status line after displaying a link code. Three cases:
     * <ol>
     *   <li>Bot ready + name known → clickable name that opens "discord.com/users/<id>" so the
     *       player can click straight into the bot's DM.</li>
     *   <li>Bot ready + name unknown → plain instruction text.</li>
     *   <li>Bot down / DI absent → warning that the code is saved and will work when the bot
     *       returns (the player should run /dclink again then).</li>
     * </ol>
     */
    private void sendBotLine(ServerPlayer player) {
        if (!DcIntegrationCompat.isBotReady()) {
            // Try to (re-)register on the bot — handles the case where the bot was down at server
            // start and has since come back (DI reconnected and JDA is now available again).
            tryReinstallBot();
            // Re-check after the install attempt.
            if (!DcIntegrationCompat.isBotReady()) {
                send(player, Config.MSG_BOT_UNAVAILABLE.get());
                return;
            }
        }
        String botName = DcIntegrationCompat.getBotName();
        String botId   = DcIntegrationCompat.getBotId();
        if (botName == null || botName.isBlank()) {
            send(player, Config.MSG_LINK_INSTRUCTION.get());
            return;
        }
        // Build prefix from the config message (everything before %s placeholder)
        String template = Config.MSG_LINK_BOT.get();
        int pct = template.indexOf("%s");
        String prefix = pct >= 0 ? template.substring(0, pct) : template;
        String suffix = pct >= 0 ? template.substring(pct + 2) : "";

        var clickable = Component.literal(botName)
                .withStyle(s -> s
                        .withColor(ChatFormatting.WHITE)
                        .withBold(true)
                        .withClickEvent(botId != null
                                ? new ClickEvent(ClickEvent.Action.OPEN_URL,
                                        "https://discord.com/users/" + botId)
                                : null)
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("§7Нажмите, чтобы открыть ЛС с ботом"))));

        var full = Component.literal(prefix).append(clickable);
        if (!suffix.isBlank()) full = full.append(Component.literal(suffix));
        player.sendSystemMessage(full);
    }

    /**
     * Attempts to (re-)register GoidaDI's JDA listener on the DI bot. Called lazily on each
     * {@code /dclink} when the bot appears to be unavailable, so that if DI has reconnected since
     * the last check we pick it up immediately without a server restart.
     *
     * <p>Guarded with {@code isLoaded()} before touching any DI class to avoid
     * {@code NoClassDefFoundError} when DI is absent, and with a try/catch for all other failures.
     */
    private void tryReinstallBot() {
        if (!DcIntegrationCompat.isLoaded()) return;
        MinecraftServer s = server;
        if (s == null) return;
        try {
            boolean installed = DcBotBridge.install(s, this, db);
            if (installed) LOG.info("Bot reconnected — GoidaDI handler re-registered.");
        } catch (Throwable t) {
            LOG.debug("tryReinstallBot skipped: {}", t.toString());
        }
    }

    private java.util.function.Function<Throwable, Void> logErr(String what) {
        return ex -> {
            LOG.error("{} failed", what, ex);
            return null;
        };
    }
}
