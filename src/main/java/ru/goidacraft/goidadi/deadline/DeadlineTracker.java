package ru.goidacraft.goidadi.deadline;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.goidacraft.goidadi.Config;
import ru.goidacraft.goidadi.compat.DcIntegrationCompat;
import ru.goidacraft.goidadi.data.LinkDatabase;
import ru.goidacraft.goidadi.data.LinkRecord;
import ru.goidacraft.goidadi.gate.DcGateManager;
import ru.goidacraft.goidadi.link.LinkService;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides, the moment a player is fully let into the game (the GoidaAuth "authorized" signal, or a
 * polling fallback), what mandatory-link state they are in: first-time greeting, reminder, lock (3A)
 * or kick (3C). Establishes the grace window on a player's first full join with the mod installed,
 * which is exactly how pre-existing players get their N days.
 */
public final class DeadlineTracker {
    private static final Logger LOG = LoggerFactory.getLogger(DeadlineTracker.class);

    private final LinkDatabase db;
    private final DcGateManager gate;
    private final LinkService linkService;
    private volatile MinecraftServer server;

    /** Guards against a single login delivering the authorized signal more than once. */
    private final Set<UUID> processedThisSession = ConcurrentHashMap.newKeySet();
    /** Avoids spamming the admin channel with the same expiry every rejoin within a session. */
    private final Set<UUID> notifiedExpired = ConcurrentHashMap.newKeySet();

    public DeadlineTracker(LinkDatabase db, DcGateManager gate, LinkService linkService) {
        this.db = db;
        this.gate = gate;
        this.linkService = linkService;
    }

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    public void onLogout(UUID uuid) {
        processedThisSession.remove(uuid);
        notifiedExpired.remove(uuid);
    }

    /** Entry point invoked when GoidaAuth (or the fallback) reports a player as authorized. */
    public void onAuthorized(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (!processedThisSession.add(uuid)) return; // already handled this session
        String name = player.getGameProfile().getName();
        Instant now = Instant.now();
        int graceDays = Config.GRACE_DAYS.get();

        db.compute(c -> {
            Optional<LinkRecord> existing = LinkDatabase.findByUuidSync(c, uuid);
            if (existing.isEmpty()) {
                LinkRecord created = LinkDatabase.ensureRowSync(c, uuid, name, now,
                        now.plus(graceDays, ChronoUnit.DAYS));
                return new Object[]{created, Boolean.TRUE};
            }
            // keep the name fresh
            LinkDatabase.ensureRowSync(c, uuid, name, existing.get().firstSeen(), existing.get().deadline());
            return new Object[]{LinkDatabase.findByUuidSync(c, uuid).orElse(existing.get()), Boolean.FALSE};
        }).thenAccept(arr -> onServer(() -> {
            ServerPlayer p = online(uuid);
            if (p == null) return;
            LinkRecord rec = (LinkRecord) arr[0];
            boolean isNew = (Boolean) arr[1];
            handle(p, rec, isNew, now);
        })).exceptionally(ex -> {
            LOG.error("deadline onAuthorized failed for {}", name, ex);
            return null;
        });
    }

    private void handle(ServerPlayer player, LinkRecord rec, boolean isNew, Instant now) {
        if (rec.isLinked()) {
            // already linked — make sure no stale lock remains
            gate.release(player.getUUID());
            return;
        }

        if (isNew) {
            LinkService.send(player, String.format(Config.MSG_WELCOME.get(), Config.GRACE_DAYS.get()));
            return;
        }

        if (rec.isExpired(now)) {
            activateExpired(player, rec);
            return;
        }

        // still within grace — remind
        int daysLeft = daysLeft(now, rec.deadline());
        if (daysLeft <= 1) {
            LinkService.send(player, Config.MSG_LAST_DAY.get());
        } else {
            LinkService.send(player, String.format(Config.MSG_REMINDER.get(), daysLeft));
        }
    }

    private void activateExpired(ServerPlayer player, LinkRecord rec) {
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().getName();

        boolean botReady = DcIntegrationCompat.isBotReady();
        boolean kick = Config.KICK_MODE.get()
                || (!botReady && Config.PAUSE_DEADLINE_WHEN_BOT_DOWN.get());

        if (notifiedExpired.add(uuid)) {
            DcIntegrationCompat.sendAdminLog(Config.ADMIN_LOG_CHANNEL_ID.get(),
                    String.format(Config.DC_NOTIFY_EXPIRED.get(), name));
        }

        if (kick) {
            linkService.generateKickCode(uuid, name).thenAccept(code -> onServer(() -> {
                ServerPlayer p = online(uuid);
                if (p == null) return;
                String botName = DcIntegrationCompat.getBotName();
                String botLine = (botName != null && !botName.isBlank())
                        ? "\nБот: " + botName
                        : "";
                p.connection.disconnect(Component.literal(
                        String.format(Config.MSG_KICK_DEADLINE.get(), code) + botLine));
            })).exceptionally(ex -> {
                LOG.error("kick-code generation failed for {}", name, ex);
                return null;
            });
        } else {
            gate.lock(player);
        }
    }

    private static int daysLeft(Instant now, Instant deadline) {
        long secs = Duration.between(now, deadline).getSeconds();
        if (secs <= 0) return 0;
        return (int) Math.ceil(secs / 86400.0);
    }

    private ServerPlayer online(UUID uuid) {
        MinecraftServer s = server;
        return s == null ? null : s.getPlayerList().getPlayer(uuid);
    }

    private void onServer(Runnable r) {
        MinecraftServer s = server;
        if (s != null) s.execute(r);
    }
}
