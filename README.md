<div align="center">

<h1>🔐 GoidaAuth</h1>

[![Latest Release](https://img.shields.io/github/v/release/Yukovsky/GoidaAuth?style=flat-square&label=release&color=2ea44f)](https://github.com/Yukovsky/GoidaAuth/releases)
[![Minecraft](https://img.shields.io/badge/MC-1.21.1-4a90d9?style=flat-square&logo=minecraft&logoColor=white)](https://www.minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.193+-e8870a?style=flat-square)](https://neoforged.net/)
[![Java](https://img.shields.io/badge/Java-21-ed8b00?style=flat-square&logo=openjdk&logoColor=white)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-8b949e?style=flat-square)](LICENSE)
[![Build](https://img.shields.io/github/actions/workflow/status/Yukovsky/GoidaAuth/build.yml?style=flat-square&logo=github-actions&logoColor=white)](https://github.com/Yukovsky/GoidaAuth/actions)

**Hybrid authentication for offline-mode NeoForge 1.21.1 servers.**  
Cracked players log in with a password. Premium players authenticate automatically via Mojang — zero extra setup.

<table>
<tr>
<td align="center"><a href="https://github.com/Yukovsky/GoidaAuth/releases"><b>📦 Releases</b></a></td>
<td align="center"><a href="https://github.com/Yukovsky/GoidaAuth/issues"><b>🐛 Issues</b></a></td>
<td align="center"><a href="https://github.com/Yukovsky/GoidaAuthVelocity"><b>🔀 Velocity Companion</b></a></td>
</tr>
</table>

</div>

---

## How Authentication Works

```
Player connects
│
├─ Name exists in Mojang API?
│   ├─ YES → mod initiates real encryption handshake → Mojang hasJoined check
│   │             ├─ verified  → ✅ auto-login (no password needed)
│   │             └─ failed    → ❌ kicked  (name is taken by a licensed account)
│   │
│   └─ NO  → player is cracked
│                 ├─ already registered → /login <password>
│                 └─ new player         → /register <password> <password>
│
└─ Session valid (same IP, not expired)? → ✅ auto-login without password prompt
```

<details>
<summary><b>Behind a Velocity proxy with GoidaAuthVelocity</b></summary>

```
Player connects to Velocity proxy
│
├─ DB: premium = true  → forceOnlineMode  → Mojang handshake at proxy level
│                                             → backend receives signed textures → ✅ auto-login
├─ DB: premium = false → forceOfflineMode → backend → /login
└─ New player          → forceOfflineMode → backend → /register
                          └─ (Mojang check optional, see GoidaAuthVelocity config)
```

Premium verification happens **before** the player reaches your NeoForge server.  
See [GoidaAuthVelocity](https://github.com/Yukovsky/GoidaAuthVelocity) for setup.

</details>

---

## Deployment Modes

|  | **Standalone** | **Behind Velocity** |
|---|---|---|
| Premium check | Mod calls Mojang `hasJoined` directly | Proxy handles handshake; mod trusts signed `textures` |
| Database | H2 embedded · zero-config · jarJar'd | Shared MySQL / MariaDB with GoidaAuthVelocity |
| Extra components | None | [GoidaAuthVelocity](https://github.com/Yukovsky/GoidaAuthVelocity) on the proxy |
| Best for | Single-backend servers | Multi-backend proxy networks |

Both modes are fully supported. Start standalone, migrate to proxy later by switching `database.mode` to `mysql`.

---

## Features

<table>
<tr><th>Category</th><th>Details</th></tr>
<tr><td><b>Premium autologin</b></td><td>Real Mojang <code>hasJoined</code> verification — same flow as FastLogin. Impostors are kicked.</td></tr>
<tr><td><b>Cracked registration</b></td><td><code>/register &lt;pass&gt; &lt;pass&gt;</code> and <code>/login &lt;pass&gt;</code> via Brigadier with <code>/reg</code>, <code>/l</code> aliases.</td></tr>
<tr><td><b>Session autologin</b></td><td>Cracked players skip password on rejoin when IP matches and session is fresh (opt-in, off by default).</td></tr>
<tr><td><b>Player lockdown</b></td><td>Freeze position, blindness, slowness 255, god mode, and full chat / command / inventory block until authenticated.</td></tr>
<tr><td><b>Password hashing</b></td><td>Argon2id with configurable iterations, memory, and parallelism.</td></tr>
<tr><td><b>Database</b></td><td>H2 embedded (default, bundled via jarJar) or MySQL / MariaDB for shared proxy setups.</td></tr>
<tr><td><b>Twink protection</b></td><td>Block multi-accounting by IP or hardware fingerprint (HWID requires companion client mod).</td></tr>
<tr><td><b>Account transfer</b></td><td><code>/transferaccount</code> — moves playerdata, stats, advancements, and sidecar files between accounts.</td></tr>
<tr><td><b>Server rules</b></td><td><code>/rules</code> — displays configurable rule categories loaded from <code>config/rules.json</code>.</td></tr>
<tr><td><b>LuckPerms compat</b></td><td>Login event defer to prevent the NeoForge + LuckPerms capability race condition on join.</td></tr>
</table>

---

## Requirements

| | |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.193 or later |
| Java | 21 |
| Side | **Server only** |
| `server.properties` | `online-mode=false` |

> If `online-mode=true`, NeoForge handles Mojang auth natively — this mod is not needed.

---

## Installation

1. Download the latest `.jar` from [Releases](https://github.com/Yukovsky/GoidaAuth/releases).
2. Drop it into the server's `mods/` directory.
3. Start the server — config is generated at `config/goidaauth-common.toml`.
4. *(Optional)* Set `online-mode=false` is already required; no other server-side changes needed.

---

## Configuration

`config/goidaauth-common.toml` — auto-generated on first launch.

```toml
[database]
  mode = "h2"           # h2 | mysql | mariadb
  host = "127.0.0.1"
  port = 3306
  database = "goidaauth"
  user = "goidaauth_rw"
  password = ""

[premium]
  autologin = true
  mojang_timeout_ms = 5000
  mojang_cache_ttl_min = 360   # cache "is name premium?" for 6 h

[login]
  timeout_seconds = 60
  max_attempts = 5
  min_password_length = 4
  max_password_length = 64
  register_confirm_required = true
  allowed_commands = ["login", "l", "register", "reg", "help"]

[sessions]
  enabled = false           # session autologin for cracked players
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
  mode = "disabled"         # disabled | ip | hardware

[messages]
  login_prompt = "§eВведите §a/login <пароль> §eдля входа."
  # all 16 messages are configurable
```

---

## Commands

| Command | Alias | Who | Description |
|---|---|---|---|
| `/login <password>` | `/l` | Players | Authenticate with registered password |
| `/register <pass> <pass>` | `/reg` | Players | Create a new account |
| `/premium` | — | OP | Mark account as premium (next login will be forced online) |
| `/unpremium` | — | OP | Revert to cracked mode |
| `/transferaccount <from> <to>` | — | OP | Move playerdata between two account names |
| `/rules` | — | Players | Show server rules |

**Permission nodes** (PermissionAPI-compatible):

| Node | Default |
|---|---|
| `goidaauth.command.login` | everyone |
| `goidaauth.command.register` | everyone |
| `goidaauth.command.premium` | OP level 2 |
| `goidaauth.command.transferaccount` | OP level 2 |

---

## Building from Source

```bash
git clone https://github.com/Yukovsky/GoidaAuth.git
cd GoidaAuth
./gradlew build
```

Output: `build/libs/goidaauth-<version>.jar` · Requires Java 21.

---

## Velocity Proxy Setup

Running behind Velocity? Install [GoidaAuthVelocity](https://github.com/Yukovsky/GoidaAuthVelocity) on the proxy and switch `database.mode` to `mysql` so both share one `users` table. The proxy then handles per-player online/offline mode before connections reach the backend.

---

## License

[MIT](LICENSE) © 2026 GoidaCraft
