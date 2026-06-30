package ru.goidacraft.goidadi.gate;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import ru.goidacraft.goidadi.Config;
import ru.goidacraft.goidadi.link.LinkService;

/**
 * Applies the in-world restrictions for players locked by {@link DcGateManager}: freeze/effect upkeep
 * on tick, and cancellation of interactions, attacks, chat, item drops and non-whitelisted commands.
 * Inventory slot dragging is handled at the packet level by GoidaAuth's mixin via the registered
 * {@code InteractionBlocker} (see GoidaAuthHook); this class covers everything reachable via events.
 */
public final class DcGateEventHandler {
    private final DcGateManager gate;

    public DcGateEventHandler(DcGateManager gate) {
        this.gate = gate;
    }

    public void onTick(PlayerTickEvent.Pre event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            gate.onTick(sp);
        }
    }

    public void cancelIfGated(ICancellableEvent event, Player player) {
        if (player instanceof ServerPlayer sp && gate.isGated(sp.getUUID())) {
            event.setCanceled(true);
        }
    }

    public void onChat(ServerChatEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer sp)) return;
        if (!gate.isGated(sp.getUUID())) return;
        event.setCanceled(true);
        LinkService.send(sp, Config.MSG_GATED_ACTION_BLOCKED.get());
    }

    public void onItemToss(ItemTossEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer sp)) return;
        if (!gate.isGated(sp.getUUID())) return;
        event.setCanceled(true);
        sp.getInventory().add(event.getEntity().getItem());
    }

    public void onDamage(LivingIncomingDamageEvent event) {
        if (!Config.GOD_MODE.get()) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (!gate.isGated(sp.getUUID())) return;
        event.setCanceled(true);
    }

    public void onCommand(CommandEvent event) {
        var source = event.getParseResults().getContext().getSource();
        ServerPlayer sp;
        try {
            sp = source.getPlayerOrException();
        } catch (Exception e) {
            return;
        }
        if (!gate.isGated(sp.getUUID())) return;

        var parse = event.getParseResults();
        var nodes = parse.getContext().getNodes();
        String root;
        if (!nodes.isEmpty()) {
            root = nodes.get(0).getNode().getName();
        } else {
            String raw = parse.getReader().getString().trim();
            if (raw.startsWith("/")) raw = raw.substring(1);
            int space = raw.indexOf(' ');
            root = space < 0 ? raw : raw.substring(0, space);
        }

        for (String allowed : Config.ALLOWED_COMMANDS.get()) {
            if (allowed.equalsIgnoreCase(root)) return;
        }
        event.setCanceled(true);
        LinkService.send(sp, Config.MSG_GATED_ACTION_BLOCKED.get());
    }
}
