package ru.goidacraft.goidadi;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * All tunables and player-facing texts for GoidaDI. Messages support {@code %d} placeholders where
 * documented (days remaining, link code). Russian defaults, mirroring GoidaAuth's config style.
 */
public final class Config {
    public static final ModConfigSpec SPEC;

    // --- core ---
    public static final ModConfigSpec.IntValue GRACE_DAYS;
    public static final ModConfigSpec.IntValue CODE_TTL_MIN;
    public static final ModConfigSpec.IntValue CODE_LENGTH_DIGITS;
    public static final ModConfigSpec.BooleanValue KICK_MODE;
    public static final ModConfigSpec.BooleanValue PAUSE_DEADLINE_WHEN_BOT_DOWN;

    // --- lock restrictions (mirror GoidaAuth) ---
    public static final ModConfigSpec.BooleanValue APPLY_BLINDNESS;
    public static final ModConfigSpec.BooleanValue APPLY_SLOWNESS;
    public static final ModConfigSpec.BooleanValue HIDE_FROM_OTHER_PLAYERS;
    public static final ModConfigSpec.BooleanValue FREEZE_PLAYER;
    public static final ModConfigSpec.BooleanValue GOD_MODE;
    public static final ModConfigSpec.IntValue EFFECT_REFRESH_SEC;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ALLOWED_COMMANDS;

    // --- DI integration extras ---
    public static final ModConfigSpec.BooleanValue MIRROR_TO_DC_INTEGRATION;
    public static final ModConfigSpec.ConfigValue<String> VERIFIED_ROLE_ID;
    public static final ModConfigSpec.ConfigValue<String> ADMIN_LOG_CHANNEL_ID;
    public static final ModConfigSpec.BooleanValue ENABLE_WHOIS_COMMAND;

    // --- messages ---
    public static final ModConfigSpec.ConfigValue<String> MSG_WELCOME;            // %d = days
    public static final ModConfigSpec.ConfigValue<String> MSG_REMINDER;           // %d = days left
    public static final ModConfigSpec.ConfigValue<String> MSG_LAST_DAY;
    public static final ModConfigSpec.ConfigValue<String> MSG_GATED;
    public static final ModConfigSpec.ConfigValue<String> MSG_GATED_ACTION_BLOCKED;
    public static final ModConfigSpec.ConfigValue<String> MSG_LINK_CODE;          // %d = code
    public static final ModConfigSpec.ConfigValue<String> MSG_LINK_TTL;           // %d = minutes
    public static final ModConfigSpec.ConfigValue<String> MSG_LINK_BOT;           // %s = bot name (clickable)
    public static final ModConfigSpec.ConfigValue<String> MSG_LINK_INSTRUCTION;   // fallback when bot name unknown
    public static final ModConfigSpec.ConfigValue<String> MSG_BOT_UNAVAILABLE;
    public static final ModConfigSpec.ConfigValue<String> MSG_LINK_SUCCESS;       // %s = discord name
    public static final ModConfigSpec.ConfigValue<String> MSG_ALREADY_LINKED;     // %s = discord name
    public static final ModConfigSpec.ConfigValue<String> MSG_NOT_LINKED;
    public static final ModConfigSpec.ConfigValue<String> MSG_UNLINK_SUCCESS;
    public static final ModConfigSpec.ConfigValue<String> MSG_UNLINK_CONFIRM;
    public static final ModConfigSpec.ConfigValue<String> MSG_OVERRIDE_PROMPT;    // %d = code
    public static final ModConfigSpec.ConfigValue<String> MSG_KICK_DEADLINE;      // %d = code
    public static final ModConfigSpec.ConfigValue<String> MSG_STATUS_LINKED;      // %s name
    public static final ModConfigSpec.ConfigValue<String> MSG_STATUS_PENDING;     // %d days
    public static final ModConfigSpec.ConfigValue<String> MSG_STATUS_EXPIRED;

    // --- discord-side (bot DM) texts ---
    public static final ModConfigSpec.ConfigValue<String> DC_MSG_LINKED;          // %s = mc name
    public static final ModConfigSpec.ConfigValue<String> DC_MSG_BAD_CODE;
    public static final ModConfigSpec.ConfigValue<String> DC_MSG_DISCORD_TAKEN;
    public static final ModConfigSpec.ConfigValue<String> DC_NOTIFY_LINKED;       // %s mc, %s discord
    public static final ModConfigSpec.ConfigValue<String> DC_NOTIFY_EXPIRED;      // %s mc

    static {
        var b = new ModConfigSpec.Builder();

        b.comment("Core mandatory-link settings").push("core");
        GRACE_DAYS = b.comment("Days a player has to link Discord, counted from their first full join with the mod.")
                .defineInRange("grace_days", 3, 0, 3650);
        CODE_TTL_MIN = b.comment("How many minutes a generated link code stays valid.")
                .defineInRange("code_ttl_minutes", 60, 1, 10080);
        CODE_LENGTH_DIGITS = b.comment("Number of digits in a link code.")
                .defineInRange("code_length_digits", 6, 4, 9);
        KICK_MODE = b.comment("If true, expired-and-unlinked players are kicked with their code instead of",
                        "being locked down in-world. Also used automatically when the Discord bot is unavailable.")
                .define("kick_mode", false);
        PAUSE_DEADLINE_WHEN_BOT_DOWN = b.comment(
                        "If true, do not start the in-world lockdown while the Discord bot/DI is unavailable",
                        "(kick with explanation instead), so players are not punished for downtime.")
                .define("pause_deadline_when_bot_down", true);
        b.pop();

        b.comment("Restrictions applied to a locked (expired & unlinked) player").push("lock");
        APPLY_BLINDNESS = b.define("blindness", true);
        APPLY_SLOWNESS = b.define("slowness", true);
        HIDE_FROM_OTHER_PLAYERS = b.comment("Make locked players invisible to others.")
                .define("invisible", false);
        FREEZE_PLAYER = b.comment("Teleport the player back to their position every tick while locked.")
                .define("freeze", true);
        GOD_MODE = b.comment("Cancel all incoming damage to locked players.")
                .define("god_mode", true);
        EFFECT_REFRESH_SEC = b.comment("How often (seconds) lock effects are re-applied so they never expire.")
                .defineInRange("effect_refresh_seconds", 30, 5, 600);
        ALLOWED_COMMANDS = b.comment("Commands a locked player may still run (without leading slash).")
                .defineList("allowed_commands",
                        List.of("dclink", "dcstatus", "dcunlink", "help"),
                        () -> "",
                        o -> o instanceof String);
        b.pop();

        b.comment("Discord Integration extras (all optional / best-effort)").push("discord");
        MIRROR_TO_DC_INTEGRATION = b.comment("Also write links into Discord Integration's LinkedPlayers.json.")
                .define("mirror_to_dcintegration", true);
        VERIFIED_ROLE_ID = b.comment("Discord role ID to grant on link and remove on unlink. Empty = disabled.")
                .define("verified_role_id", "");
        ADMIN_LOG_CHANNEL_ID = b.comment("Discord channel ID for admin notifications (link/expiry). Empty = disabled.")
                .define("admin_log_channel_id", "");
        ENABLE_WHOIS_COMMAND = b.comment("Register the !whois text command on the bot for moderators.")
                .define("enable_whois_command", true);
        b.pop();

        b.comment("Player-facing messages (Russian by default). %d/%s are replaced as documented.").push("messages");
        MSG_WELCOME = b.define("welcome",
                "§6[GoidaDI] §eНа этом сервере обязательна привязка Discord. У вас есть §f%d §eдн. — используйте §a/dclink§e.");
        MSG_REMINDER = b.define("reminder",
                "§6[GoidaDI] §eДо обязательной привязки Discord осталось §f%d §eдн. Используйте §a/dclink§e.");
        MSG_LAST_DAY = b.define("last_day",
                "§c[GoidaDI] Сегодня последний день для привязки Discord! Используйте §a/dclink§c.");
        MSG_GATED = b.define("gated",
                "§c[GoidaDI] Срок привязки Discord истёк. Вы не можете играть, пока не привяжете аккаунт через §a/dclink§c.");
        MSG_GATED_ACTION_BLOCKED = b.define("gated_action_blocked",
                "§cСначала привяжите Discord: §a/dclink§c.");
        MSG_LINK_CODE = b.define("link_code", "§aВаш код привязки: §f§l%d");
        MSG_LINK_TTL = b.comment("Shown after the code; %d = TTL in minutes.")
                .define("link_ttl", "§7Код действителен §f%d §7мин. Отправьте его боту до истечения времени.");
        MSG_LINK_BOT = b.comment("Shown after the TTL; %s = bot username. Text is clickable — opens bot DM in Discord.")
                .define("link_bot", "§eЛичные сообщения боту: §f%s §e(нажмите на имя, чтобы открыть)");
        MSG_LINK_INSTRUCTION = b.comment("Fallback shown only when the bot name cannot be retrieved.")
                .define("link_instruction",
                "§eНапишите этот код в личные сообщения боту Discord. Код действует ограниченное время.");
        MSG_BOT_UNAVAILABLE = b.comment("Shown when DI/bot is offline and the player runs /dclink.")
                .define("bot_unavailable",
                "§cБот Discord сейчас недоступен — используйте §a/dclink§c когда бот снова заработает.");
        MSG_LINK_SUCCESS = b.define("link_success", "§a✔ Discord привязан: §f%s§a. Ограничения сняты.");
        MSG_ALREADY_LINKED = b.define("already_linked",
                "§eВаш аккаунт уже привязан к Discord §f%s§e. Чтобы сменить — подтвердите переопределение новым кодом.");
        MSG_NOT_LINKED = b.define("not_linked", "§eВаш Discord не привязан. Используйте §a/dclink§e.");
        MSG_UNLINK_SUCCESS = b.define("unlink_success", "§aПривязка Discord удалена.");
        MSG_UNLINK_CONFIRM = b.define("unlink_confirm",
                "§eПовторите §a/dcunlink confirm §eдля подтверждения отвязки Discord.");
        MSG_OVERRIDE_PROMPT = b.define("override_prompt",
                "§eПереопределение привязки. Новый код: §f§l%d§e — напишите его боту с НОВОГО аккаунта Discord.");
        MSG_KICK_DEADLINE = b.define("kick_deadline",
                "Срок привязки Discord истёк.\nВаш код привязки: %d\nНапишите его в ЛС боту Discord и зайдите снова.");
        MSG_STATUS_LINKED = b.define("status_linked", "§aDiscord привязан: §f%s§a.");
        MSG_STATUS_PENDING = b.define("status_pending",
                "§eDiscord не привязан. Осталось дней: §f%d§e. Используйте §a/dclink§e.");
        MSG_STATUS_EXPIRED = b.define("status_expired",
                "§cСрок привязки истёк, доступ ограничен. Используйте §a/dclink§c.");
        b.pop();

        b.comment("Discord-side texts sent by the bot").push("discord_messages");
        DC_MSG_LINKED = b.define("dc_linked", "✅ Аккаунт Minecraft **%s** успешно привязан к вашему Discord.");
        DC_MSG_BAD_CODE = b.define("dc_bad_code", "❌ Неверный или просроченный код привязки.");
        DC_MSG_DISCORD_TAKEN = b.define("dc_discord_taken",
                "❌ Этот Discord уже привязан к другому аккаунту Minecraft.");
        DC_NOTIFY_LINKED = b.define("dc_notify_linked", "🔗 **%s** привязал Discord %s.");
        DC_NOTIFY_EXPIRED = b.define("dc_notify_expired", "⏰ У игрока **%s** истёк срок привязки — доступ ограничен.");
        b.pop();

        SPEC = b.build();
    }

    private Config() {}
}
