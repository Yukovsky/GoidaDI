package ru.goidacraft.goidadi.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import ru.goidacraft.goidadi.Config;
import ru.goidacraft.goidadi.data.LinkDatabase;
import ru.goidacraft.goidadi.data.LinkRecord;
import ru.goidacraft.goidadi.link.LinkService;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Player commands: {@code /dclink}, {@code /dcstatus}, {@code /dcunlink}. */
public final class DcPlayerCommands {
    private DcPlayerCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d, LinkService linkService, LinkDatabase db) {
        d.register(LiteralArgumentBuilder.<CommandSourceStack>literal("dclink")
                .requires(src -> true)
                .executes(ctx -> handleLink(ctx, linkService)));

        d.register(LiteralArgumentBuilder.<CommandSourceStack>literal("dcstatus")
                .requires(src -> true)
                .executes(ctx -> handleStatus(ctx, db)));

        d.register(LiteralArgumentBuilder.<CommandSourceStack>literal("dcunlink")
                .requires(src -> true)
                .executes(ctx -> handleUnlink(ctx, db, linkService, false))
                .then(Commands.literal("confirm")
                        .executes(ctx -> handleUnlink(ctx, db, linkService, true))));
    }

    private static int handleLink(CommandContext<CommandSourceStack> ctx, LinkService linkService) {
        ServerPlayer p = player(ctx);
        if (p == null) return 0;
        linkService.startLink(p);
        return 1;
    }

    private static int handleStatus(CommandContext<CommandSourceStack> ctx, LinkDatabase db) {
        ServerPlayer p = player(ctx);
        if (p == null) return 0;
        db.findByUuid(p.getUUID()).thenAccept(opt -> p.server.execute(() -> {
            ServerPlayer pl = p.server.getPlayerList().getPlayer(p.getUUID());
            if (pl == null) return;
            Instant now = Instant.now();
            if (opt.isPresent() && opt.get().isLinked()) {
                LinkRecord r = opt.get();
                LinkService.send(pl, String.format(Config.MSG_STATUS_LINKED.get(),
                        r.discordName() != null ? r.discordName() : r.discordId()));
            } else if (opt.isPresent() && opt.get().isExpired(now)) {
                LinkService.send(pl, Config.MSG_STATUS_EXPIRED.get());
            } else if (opt.isPresent()) {
                long secs = Duration.between(now, opt.get().deadline()).getSeconds();
                int days = secs <= 0 ? 0 : (int) Math.ceil(secs / 86400.0);
                LinkService.send(pl, String.format(Config.MSG_STATUS_PENDING.get(), days));
            } else {
                LinkService.send(pl, Config.MSG_NOT_LINKED.get());
            }
        }));
        return 1;
    }

    private static int handleUnlink(CommandContext<CommandSourceStack> ctx, LinkDatabase db,
                                    LinkService linkService, boolean confirmed) {
        ServerPlayer p = player(ctx);
        if (p == null) return 0;
        db.findByUuid(p.getUUID()).thenAccept(opt -> p.server.execute(() -> {
            ServerPlayer pl = p.server.getPlayerList().getPlayer(p.getUUID());
            if (pl == null) return;
            if (opt.isEmpty() || !opt.get().isLinked()) {
                LinkService.send(pl, Config.MSG_NOT_LINKED.get());
                return;
            }
            if (!confirmed) {
                LinkService.send(pl, Config.MSG_UNLINK_CONFIRM.get());
                return;
            }
            linkService.unlink(pl.getUUID()).thenAccept(ok -> p.server.execute(() -> {
                ServerPlayer pp = p.server.getPlayerList().getPlayer(p.getUUID());
                if (pp != null) LinkService.send(pp, Config.MSG_UNLINK_SUCCESS.get());
            }));
        }));
        return 1;
    }

    private static ServerPlayer player(CommandContext<CommandSourceStack> ctx) {
        try {
            return ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            return null;
        }
    }
}
