package ru.goidacraft.goidadi.gate;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;
import ru.goidacraft.goidadi.Config;
import ru.goidacraft.goidadi.data.LinkDatabase;
import ru.goidacraft.goidadi.link.LinkService;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the "locked because the Discord deadline passed and the player has not linked" state. Mirrors
 * GoidaAuth's lockdown approach (effects + tick-freeze) but in its own {@link ConcurrentHashMap}
 * state, so it never touches GoidaAuth's sessions. The lock is released the instant a link succeeds.
 *
 * <p>Per-player state lives in a concurrent map keyed by UUID, so concurrent joins/links/ticks are
 * safe. All player-facing mutations are expected to run on the server thread (callers ensure this).
 */
public final class DcGateManager {

    private static final class GateState {
        volatile Vec3 pos;
        volatile float yaw;
        volatile float pitch;
        volatile long lastEffectTick;
    }

    private final LinkDatabase db;
    private final ConcurrentHashMap<UUID, GateState> gated = new ConcurrentHashMap<>();
    private volatile MinecraftServer server;

    public DcGateManager(LinkDatabase db) {
        this.db = db;
    }

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    public boolean isGated(UUID uuid) {
        return gated.containsKey(uuid);
    }

    /** Locks the player: records freeze position, applies effects, warns them. Idempotent. */
    public void lock(ServerPlayer player) {
        UUID uuid = player.getUUID();
        GateState st = gated.computeIfAbsent(uuid, u -> new GateState());
        st.pos = player.position();
        st.yaw = player.getYRot();
        st.pitch = player.getXRot();
        applyEffects(player);
        st.lastEffectTick = player.server.getTickCount();
        LinkService.send(player, Config.MSG_GATED.get());
        player.server.getCommands().sendCommands(player);
    }

    /** Releases the player: removes effects, restores command tree. Idempotent. */
    public void release(UUID uuid) {
        if (gated.remove(uuid) == null) return;
        ServerPlayer player = online(uuid);
        if (player == null) return;
        player.removeEffect(MobEffects.BLINDNESS);
        player.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        player.removeEffect(MobEffects.INVISIBILITY);
        player.server.getCommands().sendCommands(player);
    }

    public void onLogout(UUID uuid) {
        gated.remove(uuid);
    }

    /** Re-checks the DB and locks or releases accordingly (used after unlink/transfer). */
    public void reevaluate(ServerPlayer player) {
        UUID uuid = player.getUUID();
        db.findByUuid(uuid).thenAccept(opt -> onServer(() -> {
            ServerPlayer p = online(uuid);
            if (p == null) return;
            boolean shouldGate = opt.isPresent() && opt.get().isExpired(Instant.now());
            if (shouldGate && !isGated(uuid)) lock(p);
            else if (!shouldGate && isGated(uuid)) release(uuid);
        }));
    }

    /** Per-tick maintenance for a gated player: freeze in place and keep effects topped up. */
    public void onTick(ServerPlayer player) {
        GateState st = gated.get(player.getUUID());
        if (st == null) return;

        if (Config.FREEZE_PLAYER.get() && st.pos != null
                && player.position().distanceToSqr(st.pos) > 0.0625) {
            player.connection.teleport(st.pos.x, st.pos.y, st.pos.z, st.yaw, st.pitch);
        }

        long now = player.server.getTickCount();
        if (now - st.lastEffectTick >= Config.EFFECT_REFRESH_SEC.get() * 20L) {
            applyEffects(player);
            st.lastEffectTick = now;
        }
    }

    private void applyEffects(ServerPlayer player) {
        int duration = (Config.EFFECT_REFRESH_SEC.get() + 5) * 20;
        if (Config.APPLY_BLINDNESS.get()) {
            player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, duration, 0, false, false, false));
        }
        if (Config.APPLY_SLOWNESS.get()) {
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, duration, 255, false, false, false));
        }
        if (Config.HIDE_FROM_OTHER_PLAYERS.get()) {
            player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, duration, 0, false, false, false));
        }
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
