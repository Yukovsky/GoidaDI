package ru.goidacraft.goidadi.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import ru.goidacraft.goidadi.Config;
import ru.goidacraft.goidadi.compat.DcIntegrationCompat;
import ru.goidacraft.goidadi.compat.NameResolver;
import ru.goidacraft.goidadi.data.LinkDatabase;
import ru.goidacraft.goidadi.data.LinkRecord;
import ru.goidacraft.goidadi.gate.DcGateManager;
import ru.goidacraft.goidadi.imports.BulkImporter;
import ru.goidacraft.goidadi.link.LinkService;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /dcadmin <subcommand>} — full CRUDL plus unlock / deadline management / import / export.
 * Gated by {@code LEVEL_GAMEMASTERS} (OP), matching GoidaAuth's admin commands.
 */
public final class DcAdminCommands {
    private DcAdminCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d, LinkService linkService,
                                LinkDatabase db, DcGateManager gate) {
        LiteralArgumentBuilder<CommandSourceStack> root = LiteralArgumentBuilder.<CommandSourceStack>literal("dcadmin")
                .requires(src -> src.hasPermission(Commands.LEVEL_GAMEMASTERS));

        root.then(Commands.literal("link")
                .then(playerArg()
                        .then(Commands.argument("discord", StringArgumentType.word())
                                .executes(ctx -> handleLink(ctx, linkService, null)))));

        root.then(Commands.literal("set")
                .then(playerArg()
                        .then(Commands.argument("discordId", StringArgumentType.word())
                                .executes(ctx -> handleLink(ctx, linkService, null))
                                .then(Commands.argument("discordName", StringArgumentType.greedyString())
                                        .executes(ctx -> handleLink(ctx, linkService,
                                                StringArgumentType.getString(ctx, "discordName")))))));

        root.then(Commands.literal("unlink")
                .then(Commands.argument("target", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                ctx.getSource().getServer().getPlayerNames(), b))
                        .executes(ctx -> handleUnlink(ctx, linkService, db))));

        root.then(Commands.literal("info")
                .then(Commands.argument("target", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                ctx.getSource().getServer().getPlayerNames(), b))
                        .executes(ctx -> handleInfo(ctx, db))));

        root.then(Commands.literal("list")
                .executes(ctx -> handleList(ctx, db, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> handleList(ctx, db, IntegerArgumentType.getInteger(ctx, "page")))));

        root.then(Commands.literal("search")
                .then(Commands.argument("query", StringArgumentType.greedyString())
                        .executes(ctx -> handleSearch(ctx, db))));

        root.then(Commands.literal("unlock")
                .then(playerArg()
                        .executes(ctx -> handleUnlock(ctx, gate))));

        root.then(Commands.literal("deadline")
                .then(playerArg()
                        .then(Commands.literal("set")
                                .then(Commands.argument("days", IntegerArgumentType.integer(0))
                                        .executes(ctx -> handleDeadline(ctx, db, gate, "set",
                                                IntegerArgumentType.getInteger(ctx, "days")))))
                        .then(Commands.literal("reset")
                                .executes(ctx -> handleDeadline(ctx, db, gate, "reset", 0)))
                        .then(Commands.literal("clear")
                                .executes(ctx -> handleDeadline(ctx, db, gate, "clear", 0)))));

        root.then(Commands.literal("import")
                .then(Commands.argument("file", StringArgumentType.greedyString())
                        .executes(ctx -> handleImport(ctx, db))));

        root.then(Commands.literal("export")
                .then(Commands.literal("csv").executes(ctx -> handleExport(ctx, db, "csv")))
                .then(Commands.literal("json").executes(ctx -> handleExport(ctx, db, "json"))));

        d.register(root);
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> playerArg() {
        return Commands.argument("player", StringArgumentType.word())
                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                        ctx.getSource().getServer().getPlayerNames(), b));
    }

    // ------------------------------------------------------------------
    // Create / update
    // ------------------------------------------------------------------

    private static int handleLink(CommandContext<CommandSourceStack> ctx, LinkService linkService,
                                  String providedName) {
        var source = ctx.getSource();
        MinecraftServer server = source.getServer();
        String name = StringArgumentType.getString(ctx, "player");
        // the "link" subcommand names the arg "discord"; the "set" subcommand names it "discordId"
        String discordRaw = hasArg(ctx, "discordId")
                ? StringArgumentType.getString(ctx, "discordId")
                : StringArgumentType.getString(ctx, "discord");
        String discordId = extractDiscordId(discordRaw);
        if (discordId == null) {
            source.sendFailure(Component.literal("§cНекорректный Discord ID/упоминание: §f" + discordRaw));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("§eПривязываю §f" + name + "§e к Discord §f" + discordId + "§e..."), false);

        NameResolver.resolve(server, name).thenCombine(
                CompletableFuture.supplyAsync(() -> providedName != null ? providedName
                        : DcIntegrationCompat.resolveDiscordName(discordId)),
                (uuid, dn) -> new Object[]{uuid, dn}
        ).thenCompose(arr -> linkService.adminSetLink((UUID) arr[0], name, discordId, (String) arr[1]))
                .whenComplete((ok, err) -> server.execute(() -> {
                    if (err != null || !Boolean.TRUE.equals(ok)) {
                        source.sendFailure(Component.literal("§cНе удалось привязать §f" + name));
                        return;
                    }
                    source.sendSuccess(() -> Component.literal(
                            "§aПривязка установлена: §f" + name + " §a↔ Discord §f" + discordId), true);
                }));
        return 1;
    }

    private static boolean hasArg(CommandContext<CommandSourceStack> ctx, String name) {
        try {
            StringArgumentType.getString(ctx, name);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Delete
    // ------------------------------------------------------------------

    private static int handleUnlink(CommandContext<CommandSourceStack> ctx, LinkService linkService, LinkDatabase db) {
        var source = ctx.getSource();
        MinecraftServer server = source.getServer();
        String target = StringArgumentType.getString(ctx, "target");
        String discordId = extractDiscordId(target);

        CompletableFuture<Optional<UUID>> uuidFuture;
        if (discordId != null) {
            uuidFuture = db.findByDiscordId(discordId).thenApply(opt -> opt.map(LinkRecord::mcUuid));
        } else {
            uuidFuture = NameResolver.resolve(server, target).thenApply(Optional::of);
        }

        uuidFuture.thenAccept(opt -> {
            if (opt.isEmpty()) {
                server.execute(() -> source.sendFailure(Component.literal("§cПривязка не найдена: §f" + target)));
                return;
            }
            linkService.unlink(opt.get()).thenAccept(changed -> server.execute(() -> {
                if (changed) source.sendSuccess(() -> Component.literal("§aПривязка удалена: §f" + target), true);
                else source.sendFailure(Component.literal("§eУ §f" + target + "§e не было привязки Discord."));
            }));
        });
        return 1;
    }

    // ------------------------------------------------------------------
    // Read / List / Search
    // ------------------------------------------------------------------

    private static int handleInfo(CommandContext<CommandSourceStack> ctx, LinkDatabase db) {
        var source = ctx.getSource();
        MinecraftServer server = source.getServer();
        String target = StringArgumentType.getString(ctx, "target");
        String discordId = extractDiscordId(target);

        CompletableFuture<Optional<LinkRecord>> future = discordId != null
                ? db.findByDiscordId(discordId)
                : NameResolver.resolve(server, target).thenCompose(db::findByUuid);

        future.thenAccept(opt -> server.execute(() -> {
            if (opt.isEmpty()) {
                source.sendFailure(Component.literal("§cЗапись не найдена: §f" + target));
                return;
            }
            LinkRecord r = opt.get();
            source.sendSuccess(() -> Component.literal(card(r)), false);
        }));
        return 1;
    }

    private static int handleList(CommandContext<CommandSourceStack> ctx, LinkDatabase db, int page) {
        var source = ctx.getSource();
        MinecraftServer server = source.getServer();
        int pageSize = 10;
        int offset = (page - 1) * pageSize;
        db.count().thenCombine(db.listAll(offset, pageSize), (total, records) -> new Object[]{total, records})
                .thenAccept(arr -> server.execute(() -> {
                    int total = (Integer) arr[0];
                    @SuppressWarnings("unchecked")
                    List<LinkRecord> records = (List<LinkRecord>) arr[1];
                    int pages = Math.max(1, (int) Math.ceil(total / (double) pageSize));
                    source.sendSuccess(() -> Component.literal(
                            "§eПривязки §7(стр. " + page + "/" + pages + ", всего " + total + ")§e:"), false);
                    for (LinkRecord r : records) {
                        source.sendSuccess(() -> Component.literal("§7• " + line(r)), false);
                    }
                }));
        return 1;
    }

    private static int handleSearch(CommandContext<CommandSourceStack> ctx, LinkDatabase db) {
        var source = ctx.getSource();
        MinecraftServer server = source.getServer();
        String query = StringArgumentType.getString(ctx, "query");
        db.search(query).thenAccept(records -> server.execute(() -> {
            if (records.isEmpty()) {
                source.sendSuccess(() -> Component.literal("§eНичего не найдено по запросу §f" + query), false);
                return;
            }
            source.sendSuccess(() -> Component.literal("§eРезультаты §7(" + records.size() + ")§e:"), false);
            for (LinkRecord r : records) {
                source.sendSuccess(() -> Component.literal("§7• " + line(r)), false);
            }
        }));
        return 1;
    }

    // ------------------------------------------------------------------
    // Unlock / Deadline
    // ------------------------------------------------------------------

    private static int handleUnlock(CommandContext<CommandSourceStack> ctx, DcGateManager gate) {
        var source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "player");
        ServerPlayer online = source.getServer().getPlayerList().getPlayerByName(name);
        if (online == null) {
            source.sendFailure(Component.literal("§cИгрок §f" + name + "§c не в сети."));
            return 0;
        }
        gate.release(online.getUUID());
        source.sendSuccess(() -> Component.literal("§aЛок Discord снят с игрока §f" + name + "§a (принудительно)."), true);
        return 1;
    }

    private static int handleDeadline(CommandContext<CommandSourceStack> ctx, LinkDatabase db,
                                      DcGateManager gate, String mode, int days) {
        var source = ctx.getSource();
        MinecraftServer server = source.getServer();
        String name = StringArgumentType.getString(ctx, "player");
        int graceDays = Config.GRACE_DAYS.get();

        NameResolver.resolve(server, name).thenCompose(uuid -> db.compute(c -> {
            Instant now = Instant.now();
            LinkRecord existing = LinkDatabase.findByUuidSync(c, uuid).orElse(null);
            Instant firstSeen = existing != null ? existing.firstSeen() : now;
            LinkDatabase.ensureRowSync(c, uuid, name, firstSeen, now.plus(graceDays, ChronoUnit.DAYS));
            switch (mode) {
                case "set" -> LinkDatabase.setDeadlineSync(c, uuid, now.plus(days, ChronoUnit.DAYS));
                case "reset" -> LinkDatabase.resetDeadlineSync(c, uuid, now, now.plus(graceDays, ChronoUnit.DAYS));
                case "clear" -> LinkDatabase.setDeadlineSync(c, uuid,
                        Instant.parse("9999-12-31T00:00:00Z"));
            }
            return uuid;
        })).thenAccept(uuid -> server.execute(() -> {
            ServerPlayer online = server.getPlayerList().getPlayer(uuid);
            if (online != null) gate.reevaluate(online);
            String desc = switch (mode) {
                case "set" -> "срок установлен: " + days + " дн. от текущего момента";
                case "reset" -> "отсчёт сброшен: снова " + graceDays + " дн.";
                default -> "обязанность привязки снята";
            };
            source.sendSuccess(() -> Component.literal("§aИгрок §f" + name + "§a — " + desc + "."), true);
        }));
        return 1;
    }

    // ------------------------------------------------------------------
    // Import / Export
    // ------------------------------------------------------------------

    private static int handleImport(CommandContext<CommandSourceStack> ctx, LinkDatabase db) {
        var source = ctx.getSource();
        MinecraftServer server = source.getServer();
        String raw = StringArgumentType.getString(ctx, "file");

        Path path = Path.of(raw);
        if (!path.isAbsolute()) path = BulkImporter.configDir().resolve(raw);
        Path finalPath = path;
        if (!finalPath.toFile().exists()) {
            source.sendFailure(Component.literal("§cФайл не найден: §f" + finalPath));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("§eИмпорт из §f" + finalPath + "§e..."), false);
        BulkImporter.importFile(server, db, finalPath).whenComplete((report, err) -> server.execute(() -> {
            if (err != null) {
                Throwable cause = err.getCause() != null ? err.getCause() : err;
                source.sendFailure(Component.literal("§cОшибка импорта: " + cause.getMessage()));
                return;
            }
            source.sendSuccess(() -> Component.literal(
                    "§aИмпорт завершён. Добавлено: §f" + report.added() + "§a, обновлено: §f" + report.updated()
                            + "§a, пропущено: §f" + report.skipped() + "§a, ошибок: §f" + report.errors().size()), true);
            for (String e : report.errors()) {
                source.sendSuccess(() -> Component.literal("§7• " + e), false);
            }
        }));
        return 1;
    }

    private static int handleExport(CommandContext<CommandSourceStack> ctx, LinkDatabase db, String format) {
        var source = ctx.getSource();
        MinecraftServer server = source.getServer();
        BulkImporter.export(db, format).whenComplete((path, err) -> server.execute(() -> {
            if (err != null) {
                Throwable cause = err.getCause() != null ? err.getCause() : err;
                source.sendFailure(Component.literal("§cОшибка экспорта: " + cause.getMessage()));
                return;
            }
            source.sendSuccess(() -> Component.literal("§aЭкспорт сохранён: §f" + path), true);
        }));
        return 1;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static String extractDiscordId(String arg) {
        String digits = arg.replaceAll("[<@!>]", "");
        if (digits.matches("\\d{15,20}")) return digits;
        return null;
    }

    private static String line(LinkRecord r) {
        if (r.isLinked()) {
            return "§f" + r.mcName() + " §a↔ §f"
                    + (r.discordName() != null ? r.discordName() : r.discordId());
        }
        boolean expired = r.isExpired(Instant.now());
        return "§f" + r.mcName() + (expired ? " §c(просрочен, не привязан)" : " §e(не привязан)");
    }

    private static String card(LinkRecord r) {
        StringBuilder sb = new StringBuilder();
        sb.append("§eКарточка привязки:");
        sb.append("\n§7MC: §f").append(r.mcName()).append(" §7(").append(r.mcUuid()).append("§7)");
        sb.append("\n§7Discord: §f").append(r.isLinked()
                ? (r.discordName() != null ? r.discordName() + " " : "") + "(" + r.discordId() + ")"
                : "§cне привязан");
        sb.append("\n§7Первый заход: §f").append(r.firstSeen());
        sb.append("\n§7Дедлайн: §f").append(r.deadline());
        if (r.linkedAt() != null) sb.append("\n§7Привязан: §f").append(r.linkedAt());
        if (!r.isLinked()) {
            sb.append("\n§7Статус: ").append(r.isExpired(Instant.now()) ? "§cпросрочен" : "§eв ожидании");
        }
        return sb.toString();
    }
}
