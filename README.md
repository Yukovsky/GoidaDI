<div align="center">

<h1>🔗 GoidaDI</h1>

[![Latest Release](https://img.shields.io/github/v/release/Yukovsky/GoidaDI?style=flat-square&label=release&color=2ea44f)](https://github.com/Yukovsky/GoidaDI/releases)
[![Minecraft](https://img.shields.io/badge/MC-1.21.1-4a90d9?style=flat-square&logo=minecraft&logoColor=white)](https://www.minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.228+-e8870a?style=flat-square)](https://neoforged.net/)
[![Java](https://img.shields.io/badge/Java-21-ed8b00?style=flat-square&logo=openjdk&logoColor=white)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-Apache--2.0-8b949e?style=flat-square)](LICENSE)
[![Build](https://img.shields.io/github/actions/workflow/status/Yukovsky/GoidaDI/build.yml?style=flat-square&logo=github-actions&logoColor=white)](https://github.com/Yukovsky/GoidaDI/actions)

**Mandatory Discord linking for offline-mode NeoForge servers.**
New players get a grace window to link Discord through your existing bot; unlinked players past the deadline are locked down or kicked — your choice.

<table>
<tr>
<td align="center"><a href="https://github.com/Yukovsky/GoidaDI/releases"><b>📦 Releases</b></a></td>
<td align="center"><a href="https://github.com/Yukovsky/GoidaDI/issues"><b>🐛 Issues</b></a></td>
<td align="center"><a href="https://github.com/Yukovsky/GoidaAuth"><b>🔐 GoidaAuth</b></a></td>
</tr>
</table>

</div>

---

## How It Works

GoidaDI does not implement its own Discord bot. It rides on top of [Discord Integration](https://modrinth.com/plugin/dcintegration)'s already-connected JDA bot and runs its own linking flow, independent of DI's native (online-mode-only) linking — which makes it safe on offline-mode servers where DI's own `/link` is silently disabled.

```
Player joins
│
├─ First time seen → grace window starts (default 3 days)
│
├─ Within grace window
│     ├─ /dclink → server shows a one-time code, player DMs it to the bot
│     └─ bot confirms the code → ✅ linked, role granted, restrictions never apply
│
└─ Grace window expired & still unlinked
      ├─ kick_mode = false (default) → locked in-world (blind, slowed, frozen, immortal)
      │                                  still allowed to run /dclink, /dcstatus, /dcunlink
      └─ kick_mode = true  → kicked with their code in the disconnect screen
```

If the Discord bot itself is offline, players are never punished for it: `pause_deadline_when_bot_down` holds the lockdown (falls back to a kick with instructions instead) until the bot reconnects.

```
GoidaAuth (optional)              GoidaDI                          Discord Integration (optional)
───────────────────              ────────                          ───────────────────────────────
"player authorized" event  ───▶  starts/continues the grace timer
inventory-packet guard     ◀───  "is this player gated?"
account transfer hook      ───▶  link follows the player to the new account
                                  code DM round-trip            ◀───▶  live JDA bot, role grant/remove
                                                                       admin log channel, !whois
```

Both integrations are **soft dependencies** — GoidaDI runs without either, falling back to the player-login event for timing and kick-mode for enforcement when DI is absent.

---

## Features

| Area | What it does |
|---|---|
| Grace period | Configurable per-server day count, starts on a player's first authorized join |
| Linking flow | `/dclink` issues a one-time numeric code; confirmed via Discord DM to the bot — no extra bot setup |
| Lockdown | Blindness, slowness, invisibility, freeze-in-place and damage immunity for expired-unlinked players |
| Kick mode | Optional hard-kick instead of lockdown, with the link code shown on the disconnect screen |
| Bot-down protection | Pauses enforcement (or falls back to kick) while the Discord bot is unreachable |
| GoidaAuth hook | Precise "authorized" timing signal, inventory-packet guard, and link migration on account transfer |
| Discord Integration mirror | Optionally mirrors links into DI's own `LinkedPlayers.json`, grants/removes a verified role, posts admin-log notifications |
| Admin tools | Full CRUDL via `/dcadmin` — link, unlink, info, paginated list, search, manual unlock, deadline set/reset/clear |
| Bulk import/export | Pre-seed links from CSV or JSON, export the current link table either way |
| Storage | Embedded H2 database, jarJar'd — no external database to run |
| Localization | English and Russian bundled; every player-facing string is also a config-overridable message |

---

## Requirements

| | |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.228 or later |
| Java | 21 |
| Side | Server only |

| Soft dependency | Why | If absent |
|---|---|---|
| [Discord Integration](https://modrinth.com/plugin/dcintegration) `3.0.7-1.21+` | Reuses its live JDA bot for the code-confirmation DM | Players still get codes, but cannot confirm them — server effectively runs in kick mode |
| [GoidaAuth](https://github.com/Yukovsky/GoidaAuth) `1.0+` | Precise post-authorization timing, inventory guard, transfer-aware link migration | Grace timer starts on the vanilla login event instead |

---

## Installation

1. Download the latest jar from [Releases](https://github.com/Yukovsky/GoidaDI/releases).
2. Place it in the `mods/` folder of your NeoForge server, alongside Discord Integration and/or GoidaAuth if you use them.
3. Start the server — config is created at `config/goidadi-common.toml`.

---

## Configuration

`config/goidadi-common.toml` — created on first launch. Trimmed example:

```toml
[core]
  grace_days = 3
  code_ttl_minutes = 60
  code_length_digits = 6
  kick_mode = false
  pause_deadline_when_bot_down = true

[lock]
  blindness = true
  slowness = true
  invisible = false
  freeze = true
  god_mode = true
  effect_refresh_seconds = 30
  allowed_commands = ["dclink", "dcstatus", "dcunlink", "help"]

[discord]
  mirror_to_dcintegration = true
  verified_role_id = ""
  admin_log_channel_id = ""
  enable_whois_command = true

[messages]
  # every player-facing string lives here, %d / %s placeholders documented inline
```

All player- and bot-facing text — including the Discord-side DM replies — is config-overridable, so the entire mod can be reworded or fully localized without recompiling.

---

## Commands

### Player

| Command | Description |
|---|---|
| `/dclink` | Generates (or regenerates, as an override) a one-time link code |
| `/dcstatus` | Shows current link state and days remaining |
| `/dcunlink` | Removes your Discord link (`/dcunlink confirm` to finalize) |

### Admin — `/dcadmin` (OP level 2)

| Subcommand | Description |
|---|---|
| `link <player> <discord>` / `set <player> <id> [name]` | Force-link without a confirmation code |
| `unlink <player\|discordId>` | Remove a link |
| `info <player\|discordId>` | Full record card |
| `list [page]` | Paginated link table |
| `search <query>` | Search by name/id |
| `unlock <player>` | Lift the lockdown for an online player without linking |
| `deadline <player> set <days>` / `reset` / `clear` | Adjust an individual player's grace window |
| `import <file>` | Bulk-import links from CSV or JSON |
| `export csv\|json` | Dump the full link table |

```
/dcadmin deadline Notch set 7
/dcadmin import seed-links.csv
```

CSV import format: `mcName,discordId[,discordName]`. JSON: array of `{ "mcName", "discordId", "discordName" }` objects. Imports are idempotent — unchanged rows are skipped, conflicting Discord IDs are reported instead of stolen.

---

## Building from Source

Discord Integration ships its NeoForge jar as a plain download rather than a Maven artifact, so it has to sit next to the project before compiling:

```bash
git clone https://github.com/Yukovsky/GoidaDI.git
cd GoidaDI
curl -L -o dcintegration-neoforge-3.0.7-1.21.jar \
  https://cdn.modrinth.com/data/rbJ7eS5V/versions/Tvnxofx4/dcintegration-neoforge-3.0.7-1.21.jar
./gradlew build
```

Output: `build/libs/goidadi-<version>.jar`. Requires Java 21.

GoidaAuth integration is picked up automatically and only at compile time if a sibling `../GoidaAuth` checkout exists (see `settings.gradle`); it is never required to build.

---

## License

[Apache License 2.0](LICENSE)
