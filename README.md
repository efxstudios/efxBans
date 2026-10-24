# efxBans

Punishment management for Paper servers: bans, IP bans, mutes, kicks and warnings. Commands are built on Paper's Brigadier API, so there is no command library to install. If the database can't be reached, players are refused at login instead of being let in unchecked.

The in-game messages ship in Turkish and every one of them can be changed in `messages.yml`.

<p>
  <a href="https://papermc.io"><img alt="Supported on Paper" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/paper_vector.svg" height="40"></a>
  <img alt="Spigot is not supported" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/unsupported/spigot_vector.svg" height="40">
  <img alt="Built with Java 21" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/built-with/java21_vector.svg" height="40">
  <img alt="Built with Maven" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/built-with/maven_vector.svg" height="40">
  <a href="https://github.com/efxstudios/efxBans/releases/latest"><img alt="Available on GitHub" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/github_vector.svg" height="40"></a>
  <a href="CHANGELOG.md"><img alt="Changelog" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/documentation/changelog_vector.svg" height="40"></a>
</p>

[![CI](https://github.com/efxstudios/efxBans/actions/workflows/ci.yml/badge.svg)](https://github.com/efxstudios/efxBans/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/efxstudios/efxBans)](https://github.com/efxstudios/efxBans/releases/latest)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

## What it does

- **One punishment path.** `/ban`, `/ipban`, `/mute`, `/kick`, `/warn` and your own presets (`/hile player`) all go through the same permission checks, duration limits, records and notifications. Every punishment gets a permanent ID (`#123`) and an audit log.
- **Offline players.** Bans, mutes and warnings work on offline accounts. A warning is shown the next time the player joins.
- **Several servers.** Paper servers sharing one MySQL database see each other's punishments within half a second.
- **Safe defaults.** Permanent, silent and IP-targeted punishments each need their own permission. The console is permission-checked too. IP addresses and UUIDs are hidden in messages, and values like player names and reasons are never parsed as MiniMessage tags.
- **Every text is configurable.** All messages live in `messages.yml` as MiniMessage, including multi-line ban screens.

## Installation

1. Download `efxBans-<version>.jar` from [Releases](https://github.com/efxstudios/efxBans/releases/latest) and put it in `plugins/`.
2. Start the server. `config.yml`, `messages.yml` and the SQLite database are created in `plugins/efxBans/`.
3. Grant permissions with LuckPerms or your permission plugin. Punishment commands are enabled for operators only by default.

| Server | Java |
| --- | --- |
| Paper 1.21.11 | 21+ |
| Paper 26.1 – 26.3 | 25+ |

Paper itself is required; Spigot and Bukkit are not supported. Paper forks such as Purpur should work but are not tested.

After editing the config, `/efxbans reload` is enough. Changing command names, aliases or the database needs a full restart.

## Commands

| Command | What it does |
| --- | --- |
| `/ban [-s] <player> [duration] <reason>` | Account ban |
| `/ipban [-s] <player\|IP> [duration] <reason>` | Bans the account and its last known IP |
| `/mute [-s] <player> [duration] <reason>` | Mute (chat and private message commands) |
| `/kick [-s] <player> <reason>` | Online players only |
| `/warn <player> <reason>` | Warning |
| `/unban <#ID\|player\|IP>` · `/unmute <#ID\|player>` | Remove a punishment |
| `/undo <#ID>` | Revoke any punishment; the record is kept |
| `/history <player> [page]` · `/punishment <#ID>` | History and details of one record |
| `/sorgu <player\|IP\|#ID>` | Active bans, IP bans and mutes in detail |
| `/banlist` · `/mutelist` | Active punishments, with clickable pages |
| `/alts <player>` | Accounts that used the same IP in the past |
| `/efxbans help\|status\|reload` | Help, status, reload settings |

Duration units are `s m h d w` and can be combined: `30m`, `2h`, `7d`, `1d12h`. Without a duration the punishment is permanent, which needs the `.permanent` permission. If the first word of the reason starts with a digit it is read as a duration, so a mistyped duration is rejected instead of silently becoming permanent.

`-s` makes a punishment silent: no announcement, only the staff member gets a confirmation. The target is still removed from the server and Discord is still notified.

## Permissions

| Permission | Description |
| --- | --- |
| `efxbans.<ban\|ipban\|mute>` + `.temporary` / `.permanent` | Temporary or permanent punishment; both are granted separately |
| `efxbans.kick`, `efxbans.warn` | Kick and warn |
| `efxbans.<ban\|ipban\|mute\|kick>.silent` | The `-s` flag |
| `efxbans.ipban.address` | Ban an IP directly instead of a player |
| `efxbans.unban`, `efxbans.unmute`, `efxbans.undo` | Removing and revoking |
| `efxbans.unban.address` | Unban by IP, or remove an IP ban that has no account by `#ID` |
| `efxbans.history`, `efxbans.sorgu`, `efxbans.banlist`, `efxbans.mutelist`, `efxbans.alts` | Viewing records |
| `efxbans.ip.view` | See the real IP in punishment details |
| `efxbans.notify` | Staff announcements |
| `efxbans.update.notify` | New version notice |
| `efxbans.reload`, `efxbans.admin` | Reload and status |
| `efxbans.exempt.<kind>`, `efxbans.exempt.all` | Protects the account from being punished with that kind (off by default) |
| `efxbans.bypass.exempt` | Punish exempt accounts anyway (off by default) |
| `efxbans.duration.unlimited` | Removes the duration limit and nothing else (off by default) |
| `efxbans.preset.<name>` | Preset command |

Temporary punishments are limited to 30 days by default. `permissions.duration-limits` raises the limit per permission; when several match, the highest one applies:

```yaml
permissions:
  default-maximum: 7d
  duration-limits:
    - permission: efxbans.limit.senior
      maximum: 30d
```

## Presets

Short commands for punishments you give often. `/hile Player` creates the same record as `/ipban Player 30d Hile Kullanımı` and goes through the same checks.

```yaml
presets:
  hile:
    command: hile
    aliases: [cheatban]
    type: IPBAN        # BAN, IPBAN, MUTE, KICK, WARN
    duration: 30d      # or permanent
    reason: Hile Kullanımı
    permission: efxbans.preset.hile
```

Command names may use lowercase Turkish letters (`command: küfür`); the default permission is the ASCII form, `efxbans.preset.kufur`. If you want no presets at all, write `presets: {}`.

## Messages

Everything in `messages.yml` is [MiniMessage](https://docs.advntr.dev/minimessage/format.html). Lists of lines or YAML `|` blocks become multi-line text. The available placeholders are listed at the top of the file: `{player}`, `{staff}`, `{reason}`, `{duration}`, `{remaining}`, `{id}` and so on.

Message keys added in a new version are appended to your existing file; messages you changed are left alone. The first time this happens, a backup is written to `messages.before-update.yml`.

## Discord

```yaml
discord:
  enabled: true
  url: 'https://discord.com/api/webhooks/...'
  actions: [ban, ipban, mute, kick, warn, remove, undo]
  include-ip: false
```

Only `discord.com` webhook URLs are accepted and mentions are disabled. Errors and rate limits are retried up to three times. If a notification can't be delivered, the punishment still stands.

## Update checker

Every 12 hours efxBans asks GitHub for the latest release. When there is a newer one, it says so in the console and shows a clickable message to players with `efxbans.update.notify` when they join. The check is a single GET request to `api.github.com`; nothing about your server or players is sent. Pre-releases are never suggested.

```yaml
update-checker:
  enabled: true        # false: no request is made at all
  notify-staff: true
  interval-hours: 12
```

## Database

**SQLite** is the default for a single server and needs no setup. Back it up while the server is stopped; copying only the `.db` file of a running database is not enough (the WAL files are needed too).

**MySQL** is for several servers. Create an empty database, give the user `SELECT, INSERT, UPDATE, CREATE, INDEX, ALTER` on it, and configure:

```yaml
database:
  type: mysql
  mysql:
    host: localhost
    port: 3306
    database: efxbans
    username: efxbans
    password: '...'
    tls: true    # verifies the server certificate and host name
```

Install efxBans on every Paper server; there is no Velocity or BungeeCord component. Keep server clocks in sync. The JDBC drivers come with Paper, so keep Paper up to date for driver security fixes.

If the database can't be opened, or the punishment cache can't be refreshed for 15 seconds, logins are refused and public chat is paused. This is on purpose: a database outage must not let banned players back in.

## Known limitations

- An IP ban blocks one address. On IPv6, players can change their address easily.
- `/alts` shows at most 100 accounts, and a shared IP does not prove two accounts belong to the same person.
- The Discord queue is kept in memory; notifications still waiting when the server crashes are not sent.
- There is no import from other punishment plugins or the vanilla ban list.
- efxBans takes priority over commands with the same name (`/ban` goes to efxBans). The other plugin's command keeps working under its namespace, such as `/essentials:ban`.

## Development

Building, tests, code rules and how to cut a release: [CONTRIBUTING.md](CONTRIBUTING.md). Security issues: [SECURITY.md](SECURITY.md).

```bash
./mvnw verify
```

## License

[GPL-3.0](LICENSE). If you distribute a modified version, you must share its source code under the same license.
