package ru.goidacraft.goidadi.link;

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.goidacraft.goidadi.Config;
import ru.goidacraft.goidadi.data.LinkDatabase;

import java.util.Map;

import de.erdbeerbaerlp.dcintegration.common.DiscordIntegration;
import de.erdbeerbaerlp.dcintegration.common.api.DiscordEventHandler;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

/**
 * Hooks into Discord Integration's live bot to drive GoidaDI's link flow.
 *
 * <p><b>Architecture note:</b> DI's {@link DiscordEventHandler#onDiscordPrivateMessage} is declared
 * in the API but <em>never invoked</em> by DI's {@code DiscordEventListener} — it only routes
 * guild-channel messages to registered handlers. Therefore we register a <em>separate</em> JDA
 * {@link ListenerAdapter} directly on the JDA instance to receive private/DM messages, which is
 * the actual channel where players send their link codes.
 *
 * <p>We still extend {@link DiscordEventHandler} and register it on DI for guild-channel features
 * ({@code !whois} command), but all link-code handling goes through the direct JDA listener.
 */
public final class DcBotBridge extends DiscordEventHandler {
    private static final Logger LOG = LoggerFactory.getLogger(DcBotBridge.class);

    private static volatile DcBotBridge diHandler;
    private static volatile ListenerAdapter jdaListener;
    private static volatile boolean active = false;
    private static volatile JDA savedJda;

    private final MinecraftServer server;
    private final LinkService linkService;
    private final LinkDatabase db;

    private DcBotBridge(MinecraftServer server, LinkService linkService, LinkDatabase db) {
        this.server = server;
        this.linkService = linkService;
        this.db = db;
    }

    public static boolean install(MinecraftServer server, LinkService linkService, LinkDatabase db) {
        try {
            if (DiscordIntegration.INSTANCE == null) return false;
            JDA jda = DiscordIntegration.INSTANCE.getJDA();
            if (jda == null) return false;
            if (diHandler != null) return true; // already installed

            DcBotBridge bridge = new DcBotBridge(server, linkService, db);

            // 1. DI handler — for guild-channel features (!whois etc.)
            DiscordIntegration.INSTANCE.registerEventHandler(bridge);
            diHandler = bridge;

            // 2. Direct JDA listener — for private/DM messages (link codes).
            //    DI's DiscordEventListener never routes DMs to registered DiscordEventHandlers,
            //    so we must hook JDA directly.
            ListenerAdapter pm = new ListenerAdapter() {
                @Override
                public void onMessageReceived(MessageReceivedEvent event) {
                    if (event.isFromType(ChannelType.PRIVATE)) {
                        bridge.handlePrivateDM(event);
                    }
                }
            };
            jda.addEventListener(pm);
            jdaListener = pm;
            savedJda = jda;

            active = true;
            LOG.info("Registered GoidaDI handler on the Discord Integration bot (DI handler + JDA DM listener).");
            return true;
        } catch (Throwable t) {
            LOG.error("Failed to register on the DI bot", t);
            return false;
        }
    }

    /**
     * Detaches GoidaDI's listeners from the live bot. Runs on {@code ServerStoppingEvent}.
     *
     * <p>Deliberately does <b>not</b> touch the JDA instance or any DI thread: DI sends its own
     * player-leave and server-stop messages from {@code ServerStoppedEvent} (which fires
     * <em>after</em> stopping), so killing JDA here would make those calls fail with
     * {@link java.util.concurrent.RejectedExecutionException}. The forced thread/JDA teardown is
     * deferred to {@link #forceShutdown()}.
     */
    public static void uninstall() {
        active = false;
        try {
            if (DiscordIntegration.INSTANCE != null) {
                if (diHandler != null) DiscordIntegration.INSTANCE.unregisterEventHandler(diHandler);
                JDA jda = DiscordIntegration.INSTANCE.getJDA();
                if (jda != null && jdaListener != null) jda.removeEventListener(jdaListener);
            }
        } catch (Throwable t) {
            LOG.debug("uninstall failed: {}", t.toString());
        }
        diHandler = null;
        jdaListener = null;
    }

    /**
     * Forces the lingering DI/JDA threads to terminate so the JVM can exit promptly.
     *
     * <p>Must run on {@code ServerStoppedEvent} at {@code LOWEST} priority — i.e. after DI's own
     * {@code ServerStopped} handler has sent its stop message and gracefully shut JDA down.
     * Running earlier reintroduces the {@code RejectedExecutionException} shutdown spam.
     */
    public static void forceShutdown() {
        // JDA's graceful shutdown() leaves OkHttp non-daemon threads alive for ~60s keepalive.
        // shutdownNow() forces them to terminate immediately. DI has already shut JDA down by now;
        // shutdownNow() on an already-stopped instance is a harmless no-op for the pools.
        JDA jda = savedJda;
        savedJda = null;
        if (jda != null) {
            try {
                jda.shutdownNow();
            } catch (Throwable t) {
                LOG.debug("JDA shutdownNow failed: {}", t.toString());
            }
        }

        // DI's WorkThread is a non-daemon thread that survives DI's own shutdown: after
        // processing its last job it blocks on queue.take() indefinitely, preventing JVM exit.
        // Interrupting it throws InterruptedException out of take(), allowing the thread to exit.
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            Thread t = entry.getKey();
            if (t.isDaemon() || t == Thread.currentThread()) continue;
            for (StackTraceElement el : entry.getValue()) {
                if (el.getClassName().startsWith("de.erdbeerbaerlp.dcintegration.common.WorkThread")) {
                    t.interrupt();
                    LOG.debug("Interrupted DI WorkThread: {}", t.getName());
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Private DM handler — receives link codes
    // ------------------------------------------------------------------

    void handlePrivateDM(MessageReceivedEvent event) {
        if (!active) return;
        if (event.getAuthor().isBot()) return;
        String content = event.getMessage().getContentRaw().trim();
        if (!content.matches("\\d{4,9}")) return;

        int code;
        try {
            code = Integer.parseInt(content);
        } catch (NumberFormatException e) {
            return;
        }

        String discordId   = event.getAuthor().getId();
        String discordName = event.getAuthor().getName();
        MessageChannel channel = event.getChannel();

        LOG.debug("Received link code {} from Discord user {}", code, discordId);

        linkService.confirmFromDiscord(code, discordId, discordName).thenAccept(result -> {
            if (!active) return;
            switch (result.status()) {
                case LINKED -> {
                    reply(channel, String.format(Config.DC_MSG_LINKED.get(), result.mcName()));
                    linkService.afterLinked(result);
                }
                case DISCORD_TAKEN -> reply(channel, Config.DC_MSG_DISCORD_TAKEN.get());
                case BAD_CODE, EXPIRED_CODE -> reply(channel, Config.DC_MSG_BAD_CODE.get());
            }
            LOG.debug("confirmFromDiscord result for {}: {}", discordId, result.status());
        }).exceptionally(ex -> {
            LOG.error("confirmFromDiscord failed for discordId={}", discordId, ex);
            reply(channel, Config.DC_MSG_BAD_CODE.get());
            return null;
        });
    }

    // ------------------------------------------------------------------
    // Guild-channel handler — !whois command (via DI DiscordEventHandler)
    // ------------------------------------------------------------------

    @Override
    public boolean onDiscordMessagePre(MessageReceivedEvent event) {
        if (!Config.ENABLE_WHOIS_COMMAND.get()) return false;
        if (event.getAuthor().isBot()) return false;
        String content = event.getMessage().getContentRaw().trim();
        if (!content.toLowerCase().startsWith("!whois")) return false;

        String arg = content.substring("!whois".length()).trim();
        MessageChannel channel = event.getChannel();
        if (arg.isEmpty()) {
            reply(channel, "Использование: `!whois <ник Minecraft | @упоминание | Discord ID>`");
            return true;
        }

        String discordId = extractDiscordId(arg);
        if (discordId != null) {
            db.findByDiscordId(discordId).thenAccept(opt -> reply(channel, opt
                    .map(r -> "Discord `" + discordId + "` → Minecraft **" + r.mcName() + "**")
                    .orElse("Привязка для этого Discord не найдена.")));
        } else {
            db.findByName(arg).thenAccept(opt -> reply(channel, opt
                    .filter(r -> r.isLinked())
                    .map(r -> "Minecraft **" + r.mcName() + "** → Discord "
                            + (r.discordName() != null ? r.discordName() : r.discordId()))
                    .orElse("Привязка для этого игрока не найдена.")));
        }
        return true;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static String extractDiscordId(String arg) {
        String digits = arg.replaceAll("[<@!>]", "");
        if (digits.matches("\\d{15,20}")) return digits;
        return null;
    }

    private void reply(MessageChannel channel, String message) {
        if (!active) return;
        try {
            channel.sendMessage(message).queue();
        } catch (Throwable t) {
            LOG.debug("reply failed: {}", t.toString());
        }
    }
}
