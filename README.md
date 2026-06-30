<div align="center">

# GoidaAuth

[![Latest Release](https://img.shields.io/github/v/release/Yukovsky/GoidaAuth?style=flat-square&label=latest&color=brightgreen)](https://github.com/Yukovsky/GoidaAuth/releases)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-blue?style=flat-square)](https://www.minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.193+-orange?style=flat-square)](https://neoforged.net/)
[![License](https://img.shields.io/badge/License-MIT-lightgrey?style=flat-square)](LICENSE)
[![Build](https://img.shields.io/github/actions/workflow/status/Yukovsky/GoidaAuth/build.yml?style=flat-square)](https://github.com/Yukovsky/GoidaAuth/actions)

**Hybrid premium-aware authentication mod for offline-mode NeoForge servers.**

Handles both cracked (`/register` + `/login`) and licensed (automatic Mojang `hasJoined` verification) players on the same server.

| Links | |
|---|---|
| Issues | [github.com/Yukovsky/GoidaAuth/issues](https://github.com/Yukovsky/GoidaAuth/issues) |
| Releases | [github.com/Yukovsky/GoidaAuth/releases](https://github.com/Yukovsky/GoidaAuth/releases) |
| Velocity companion | [GoidaAuthVelocity](https://github.com/Yukovsky/GoidaAuthVelocity) |

</div>

---

## Features

| Category | Description |
|---|---|
| Premium autologin | Initiates real Mojang encryption + `hasJoined` for premium usernames; impostor cracked clients with the same name are kicked |
| Cracked auth | `/register <pass> <pass>` and `/login <pass>` via Brigadier with `/reg`, `/l` aliases |
| Session autologin | Cracked players skip login on rejoin if IP matches and session is fresh (opt-in, off by default) |
| Player lockdown | Freeze, blindness, slowness 255, god mode, chat/command/inventory block until authenticated |
| Database | H2 embedded (zero-config, bundled via jarJar) or MySQL/MariaDB for shared proxy setups |
| Password hashing | Argon2id with configurable iterations, memory, parallelism |
| Twink protection | Block multi-accounting by IP or hardware fingerprint (requires companion client mod for HWID mode) |
| Account transfer | `/transferaccount` — moves playerdata, stats, advancements between two accounts |
| Server rules | `/rules` — displays configurable rule categories from `config/rules.json` |
| LuckPerms compat | Login event defer to avoid NeoForge + LuckPerms race condition on join |
| Fully configurable | All player-facing messages, timeouts, restrictions, and DB settings in one TOML file |

---

## Requirements

| | |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.193 or later |
| Java | 21 |
| Side | Server only |
| Server mode | `online-mode=false` in `server.properties` |

> **Note:** Online-mode is required to be `false`. If the server is in online-mode, NeoForge handles Mojang authentication natively and this mod is redundant.

---

## Installation

1. Download the latest jar from [Releases](https://github.com/Yukovsky/GoidaAuth/releases).
2. Place it in the `mods/` folder of your NeoForge server.
3. Start the server — config is created at `config/goidaauth-common.toml`.

---

## Velocity Proxy Setup (optional)

If the server runs behind a Velocity proxy, install the companion plugin [GoidaAuthVelocity](https://github.com/Yukovsky/GoidaAuthVelocity) on the proxy and switch the mod's database to MySQL/MariaDB so both share the same `users` table.

With this setup:
- Velocity intercepts login at the proxy level and forces per-player online/offline mode based on the shared DB.
- The mod trusts the NeoVelocity-forwarded signed `textures` property instead of calling Mojang itself.
- Premium auth is more reliable because the handshake happens before the player reaches the backend.

Without the companion plugin, the mod works fully standalone — it calls the Mojang `hasJoined` API directly, which is sufficient for single-backend setups.

---

## Configuration

`config/goidaauth-common.toml` — created on first launch.

```toml
[database]
  # h2 (embedded, default) | mysql | mariadb
  mode = "h2"
  host = "127.0.0.1"
  port = 3306
  database = "goidaauth"
  user = "goidaauth_rw"
  password = ""

[premium]
  autologin = true
  mojang_timeout_ms = 5000
  mojang_cache_ttl_min = 360

[login]
  timeout_seconds = 60
  max_attempts = 5
  min_password_length = 4
  max_password_length = 64
  register_confirm_required = true
  allowed_commands = ["login", "l", "register", "reg", "help"]

[sessions]
  enabled = false
  timeout_minutes = 10
  require_same_ip = true

[argon2]
  iterations = 3
  memory_kb = 65536
  parallelism = 1

[restrictions]
  blindness = true
  slowness = true
  freeze = true
  god_mode = true
  invisible = false
  teleport_to_spawn = false

[twink_protection]
  # disabled | ip | hardware
  mode = "disabled"

[messages]
  login_prompt = "§eВведите §a/login <пароль> §eдля входа."
  # ... all messages are configurable
```

---

## Commands

| Command | Aliases | Description |
|---|---|---|
| `/login <password>` | `/l` | Authenticate with a registered password |
| `/register <pass> <pass>` | `/reg` | Create an account |
| `/premium` | — | Mark your account as premium (admin use) |
| `/unpremium` | — | Revert to cracked mode |
| `/transferaccount <from> <to>` | — | Move playerdata between two account names |
| `/rules` | — | Show server rules |

Permission nodes (PermissionAPI-compatible, optional):

| Node | Default |
|---|---|
| `goidaauth.command.login` | all |
| `goidaauth.command.register` | all |
| `goidaauth.command.premium` | OP |
| `goidaauth.command.transferaccount` | OP |

---

## Building from Source

```bash
git clone https://github.com/Yukovsky/GoidaAuth.git
cd GoidaAuth
./gradlew build
```

Output: `build/libs/goidaauth-<version>.jar`. Requires Java 21.

---

## License

[MIT License](LICENSE)
