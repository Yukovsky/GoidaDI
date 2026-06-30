package ru.goidacraft.goidadi;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import ru.goidacraft.goidadi.commands.DcAdminCommands;
import ru.goidacraft.goidadi.commands.DcPlayerCommands;
import ru.goidacraft.goidadi.compat.DcIntegrationCompat;
import ru.goidacraft.goidadi.compat.GoidaAuthHook;
import ru.goidacraft.goidadi.compat.TransferHook;
import ru.goidacraft.goidadi.data.LinkDatabase;
import ru.goidacraft.goidadi.deadline.DeadlineTracker;
import ru.goidacraft.goidadi.gate.DcGateEventHandler;
import ru.goidacraft.goidadi.gate.DcGateManager;
import ru.goidacraft.goidadi.link.DcBotBridge;
import ru.goidacraft.goidadi.link.LinkService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Mod(GoidaDI.MODID)
public final class GoidaDI {
    public static final String MODID = "goidadi";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static GoidaDI instance;

    private final LinkDatabase database = new LinkDatabase();
    private final DcGateManager gate = new DcGateManager(database);
    private final LinkService linkService = new LinkService(database, gate);
    private final DeadlineTracker tracker = new DeadlineTracker(database, gate, linkService);
    private final DcGateEventHandler gateHandler = new DcGateEventHandler(gate);

    public GoidaDI(IEventBus modBus, ModContainer container) {
        instance = this;
        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC, "goidadi-common.toml");

        var gameBus = NeoForge.EVENT_BUS;
        gameBus.addListener(this::onServerAboutToStart);
        gameBus.addListener(this::onServerStarted);
        gameBus.addListener(EventPriority.LOWEST, this::onServerStopping);
        gameBus.addListener(EventPriority.LOWEST, this::onServerStopped);
        gameBus.addListener(EventPriority.LOWEST, this::onRegisterCommands);

        gameBus.addListener((PlayerEvent.PlayerLoggedInEvent e) -> onLogin(e));
        gameBus.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> onLogout(e));

        // Gate restrictions (only affect players locked by DcGateManager).
        gameBus.addListener((PlayerTickEvent.Pre e) -> gateHandler.onTick(e));
        gameBus.addListener((PlayerInteractEvent.RightClickBlock e) -> gateHandler.cancelIfGated(e, e.getEntity()));
        gameBus.addListener((PlayerInteractEvent.RightClickItem e) -> gateHandler.cancelIfGated(e, e.getEntity()));
        gameBus.addListener((PlayerInteractEvent.LeftClickBlock e) -> gateHandler.cancelIfGated(e, e.getEntity()));
        gameBus.addListener((PlayerInteractEvent.EntityInteract e) -> gateHandler.cancelIfGated(e, e.getEntity()));
        gameBus.addListener((PlayerInteractEvent.EntityInteractSpecific e) -> gateHandler.cancelIfGated(e, e.getEntity()));
        gameBus.addListener((AttackEntityEvent e) -> gateHandler.cancelIfGated(e, e.getEntity()));
        // HIGHEST: гейт должен отменить чат запертого игрока РАНЬШЕ, чем GoidaChat (HIGH) его
        // обработает и разошлёт. GoidaChat кооперативно уступает уже отменённое событие.
        gameBus.addListener(EventPriority.HIGHEST, (ServerChatEvent e) -> gateHandler.onChat(e));
        gameBus.addListener((CommandEvent e) -> gateHandler.onCommand(e));
        gameBus.addListener((LivingIncomingDamageEvent e) -> gateHandler.onDamage(e));
        gameBus.addListener((ItemTossEvent e) -> gateHandler.onItemToss(e));
    }

    private void onServerAboutToStart(ServerAboutToStartEvent event) {
        try {
            database.start(event.getServer());
            gate.setServer(event.getServer());
            linkService.setServer(event.getServer());
            tracker.setServer(event.getServer());
            LOGGER.info("GoidaDI database initialized");
        } catch (Exception e) {
            LOGGER.error("Failed to initialize GoidaDI database", e);
            throw new RuntimeException(e);
        }
    }

    private void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();

        // GoidaAuth integration (authorized signal + inventory block + transfer). Soft.
        if (GoidaAuthHook.isAvailable()) {
            GoidaAuthHook.register(tracker, gate);
            TransferHook.register(server, linkService);
        } else {
            LOGGER.warn("GoidaAuth not present — using login-event fallback for the grace timer; "
                    + "account-transfer link migration is unavailable.");
        }

        // Register on the DI bot. INSTANCE may not be ready yet, so retry for a while.
        if (DcIntegrationCompat.isLoaded()) {
            if (!DcBotBridge.install(server, linkService, database)) {
                new BotRetry(server).register();
            }
        } else {
            LOGGER.warn("Discord Integration not present — link confirmation via DM is unavailable; "
                    + "expired players will be handled in kick mode.");
        }

        // One-off cleanup of stale pending codes from previous runs.
        database.purgeExpiredPending(Instant.now().minus(Config.CODE_TTL_MIN.get(), ChronoUnit.MINUTES));
    }

    private void onServerStopping(ServerStoppingEvent event) {
        // Detach our listeners early, but leave JDA/DI threads alone — DI still sends its
        // player-leave and server-stop messages from ServerStoppedEvent. Killing JDA here causes
        // RejectedExecutionException spam and a stalled shutdown.
        DcBotBridge.uninstall();
        database.shutdown();
    }

    private void onServerStopped(ServerStoppedEvent event) {
        // LOWEST priority: runs after DI's own ServerStopped handler has gracefully shut JDA down.
        // Forces the lingering JDA/OkHttp/WorkThread non-daemon threads to terminate so the JVM
        // can exit promptly.
        DcBotBridge.forceShutdown();
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        DcPlayerCommands.register(event.getDispatcher(), linkService, database);
        DcAdminCommands.register(event.getDispatcher(), linkService, database, gate);
    }

    private void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        // Only used as a fallback when GoidaAuth is absent. When GoidaAuth is present the precise
        // "authorized" hook drives the tracker instead (so we never start the timer before login).
        if (GoidaAuthHook.isAvailable()) return;
        if (event.getEntity() instanceof ServerPlayer sp) {
            tracker.onAuthorized(sp);
        }
    }

    private void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            gate.onLogout(sp.getUUID());
            tracker.onLogout(sp.getUUID());
        }
    }

    public static GoidaDI get() {
        return instance;
    }

    public LinkDatabase database() { return database; }
    public DcGateManager gate() { return gate; }
    public LinkService linkService() { return linkService; }

    /**
     * Retries DI bot registration once a second until the JDA bot is up (or we give up after ~2 min).
     * One-shot per tick check; unregisters itself when done.
     */
    public static final class BotRetry {
        private final MinecraftServer server;
        private int ticks;
        private boolean done;

        BotRetry(MinecraftServer server) {
            this.server = server;
        }

        void register() {
            NeoForge.EVENT_BUS.register(this);
        }

        @SubscribeEvent
        public void onTick(ServerTickEvent.Post event) {
            if (done) return;
            if (++ticks % 20 != 0) return; // ~once per second
            boolean installed = DcBotBridge.install(server,
                    GoidaDI.get().linkService(), GoidaDI.get().database());
            if (installed || ticks > 20 * 120) {
                done = true;
                NeoForge.EVENT_BUS.unregister(this);
                if (!installed) {
                    LOGGER.warn("Discord Integration bot did not become available; "
                            + "running in kick mode for expired players.");
                }
            }
        }
    }
}
