<div align="center">

<h1>🔐 GoidaAuth</h1>

[![Latest Release](https://img.shields.io/github/v/release/Yukovsky/GoidaAuth?style=flat-square&label=release&color=2ea44f)](https://github.com/Yukovsky/GoidaAuth/releases)
[![Minecraft](https://img.shields.io/badge/MC-1.21.1-4a90d9?style=flat-square&logo=minecraft&logoColor=white)](https://www.minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.193+-e8870a?style=flat-square)](https://neoforged.net/)
[![Java](https://img.shields.io/badge/Java-21-ed8b00?style=flat-square&logo=openjdk&logoColor=white)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-Apache--2.0-8b949e?style=flat-square)](LICENSE)
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

Premium verification happens at the **proxy**, which is the only place where a real Mojang
handshake is possible on an offline-mode network. The backend then proves what happened from the
connection's UUID.

```
Player connects to Velocity proxy (GoidaAuthVelocity)
│
├─ DB: premium = true  → forceOnlineMode  → Mojang handshake at proxy level
│                                             → impostor fails and is kicked at the proxy
├─ DB: premium = false → forceOfflineMode
└─ Unknown name        → forceOfflineMode (or online, see force-online-new-premium)
                       ↓
              backend (this mod)
                       │
   ┌───────────────────┴────────────────────┐
   │ UUID ≠ offline UUID → Mojang-verified  │
   └───────────────────┬────────────────────┘
                       │
├─ verified + DB premium      → ✅ auto-login, no password
├─ verified + unknown name    → ✅ registered as licensed
├─ NOT verified + DB premium  → ❌ kicked (impostor on a licensed name)
└─ everything else            → /login <password>  ·  /register <pass> <pass>
                                 └─ or session auto-login (same IP, not expired)

then, for everyone alike → /rules → /acceptrules → play
```

**Why the UUID and not the profile properties.** Velocity assigns the real Mojang UUID after an
online-mode handshake and the derived offline UUID (`MD5("OfflinePlayer:" + name)`) otherwise, and
the client cannot choose which it gets. A signed `textures` property proves nothing: skin plugins
such as SkinsRestorer attach genuine Mojang-signed textures to cracked players.

---

## Deployment Modes

|  | **Standalone** | **Behind Velocity** |
|---|---|---|
| Premium check | None — every player uses a password | Proxy runs the handshake; backend verifies the forwarded UUID |
| Database | H2 embedded · zero-config · jarJar'd | Shared MySQL / MariaDB with GoidaAuthVelocity |
| Extra components | None | [GoidaAuthVelocity](https://github.com/Yukovsky/GoidaAuthVelocity) on the proxy |
| Best for | Single-backend servers | Multi-backend proxy networks |

Both modes are supported, but premium auto-login exists **only** behind the proxy. Start standalone,
migrate later by switching `database.mode` to `mysql` and installing the proxy plugin.

---

## Features

<table>
<tr><th>Category</th><th>Details</th></tr>
<tr><td><b>Premium autologin</b></td><td>Proxy-verified Mojang session, proven on the backend by the forwarded UUID. Impostors on a licensed name are kicked.</td></tr>
<tr><td><b>Cracked registration</b></td><td><code>/register &lt;pass&gt; &lt;pass&gt;</code> and <code>/login &lt;pass&gt;</code> via Brigadier with <code>/reg</code>, <code>/l</code> aliases.</td></tr>
<tr><td><b>Session autologin</b></td><td>Cracked players skip password on rejoin when IP matches and session is fresh (opt-in, off by default).</td></tr>
<tr><td><b>Player lockdown</b></td><td>Freeze position, blindness, slowness 255, god mode, and full chat / command / inventory block until authenticated.</td></tr>
<tr><td><b>Rules gate</b></td><td><code>/rules</code> + <code>/acceptrules</code> — the same barrier for licensed and cracked players, asked once per account and persisted.</td></tr>
<tr><td><b>Password hashing</b></td><td>BCrypt (cost 12). Legacy PBKDF2-SHA256 hashes are verified and transparently upgraded on next login.</td></tr>
<tr><td><b>Brute-force limit</b></td><td>Per-IP block on repeated wrong passwords that survives reconnects; keyed by address, not account, so nobody can lock a player out of their own account.</td></tr>
<tr><td><b>Database</b></td><td>H2 embedded (default, bundled via jarJar) or MySQL / MariaDB for shared proxy setups.</td></tr>
<tr><td><b>Twink protection</b></td><td>Block multi-accounting by IP or hardware fingerprint (HWID requires companion client mod).</td></tr>
<tr><td><b>Account transfer</b></td><td><code>/transferaccount</code> — moves playerdata, stats, advancements, and sidecar files between accounts.</td></tr>
<tr><td><b>Server rules</b></td><td>Configurable rule categories and links loaded from <code>config/goida_rules.json</code>.</td></tr>
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

> [!IMPORTANT]
> Behind a proxy, set `player-info-forwarding-mode = "modern"` in `velocity.toml` and firewall the
> backend port so it is reachable only through the proxy. Modern forwarding signs the forwarded
> profile with the shared secret; with `legacy`/`none`, or an exposed backend port, anyone can
> connect with any username and UUID they like and no backend-side check can help.

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

[login]
  timeout_seconds = 60
  rules_timeout_seconds = 300   # time to read and accept the rules
  max_attempts = 5              # wrong /login attempts per connection
  ip_block_after_attempts = 10  # wrong attempts per IP — survives reconnects
  ip_block_seconds = 600        # block duration, and how long a failure is remembered
  min_password_length = 4
  max_password_length = 64
  register_confirm_required = true
  allowed_commands = ["login", "l", "register", "reg", "help"]

[sessions]
  enabled = false           # session autologin for cracked players
  timeout_minutes = 10
  require_same_ip = true

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
| `/rules` · `/acceptrules` | — | Players | Read the rules and accept them (required once per account) |
| `/premium [confirm]` | — | Players · OP | Self-service, or `/premium <player>` for OP — marks the account licensed |
| `/unpremium [confirm]` | — | Players · OP | Revert to cracked mode; playerdata is carried back to the offline UUID |
| `/setpassword <player> <pass>` | — | OP | Set a password for an account that has none |
| `/transferaccount <from> <to>` | — | OP | Move playerdata between two account names |
| `/account <player>` · `/accountip` · `/multiaccounts` | — | OP | Look up accounts sharing an IP |

### Console-only commands

Reachable **only from the server console** — not from an operator, a command block, a function or
RCON. These erase account-linkage evidence, so they are deliberately kept away from OP: an
administrator without shell access cannot use them to cover tracks.

| Command | Description |
|---|---|
| `/goidaauth forget <holder> <target>` | Clears `last_ip` + `hwid` of **`target`**, so it stops appearing in `holder`'s shared-IP report. Only `target` is touched, so the argument order matters. |
| `/goidaauth forget <holder> ip <address>` | Same, applied to every account on that address except `holder`. |

Both print what will be cleared and require an explicit `confirm` as the last argument. Note this
clears *history*, it does not suppress future linkage: `last_ip` is written again the next time the
account logs in successfully.

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

[Apache License 2.0](LICENSE) © 2026 GoidaCraft
