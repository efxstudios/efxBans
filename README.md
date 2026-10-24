# efxBans

<!-- Copy-ready project descriptions: .github/listings/modrinth.md and .github/listings/spigot.bbcode.
     Replace BANNER_URL at the top of each listing with your public banner image URL. -->

Punishment management for Paper and Folia servers: bans, IP bans, mutes, kicks and warnings. Commands are built on Paper's Brigadier API, so there is no command library to install. If the database can't be reached, players are refused at login instead of being let in unchecked.

Messages ship in English, Turkish, German, Spanish and French, and every one of them can be edited.

<p>
  <a href="https://papermc.io"><img alt="Supported on Paper" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/paper_vector.svg" height="40"></a>
  <img alt="Won’t support Spigot" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/unsupported/spigot_vector.svg" height="40">
  <a href="https://discord.gg/efxstudios"><img alt="Chat with us on Discord" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/social/discord-plural_vector.svg" height="40"></a>
  <img alt="Built with Java 21" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/built-with/java21_vector.svg" height="40">
  <img alt="Built with Maven" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/built-with/maven_vector.svg" height="40">
  <a href="https://modrinth.com/project/efxbans/"><img alt="Available on Modrinth" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/modrinth_vector.svg" height="40"></a>
  <a href="CHANGELOG.md"><img alt="Changelog" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/documentation/changelog_vector.svg" height="40"></a>
</p>

## What it does

- **One punishment path.** `/ban`, `/ipban`, `/mute`, `/kick`, `/warn` and your own presets (`/hile player`) all go through the same permission checks, duration limits, records and notifications. Every punishment gets a permanent ID (`#123`) and an audit log.
- **Offline players.** Bans, mutes and warnings work on offline accounts. A warning is shown the next time the player joins.
- **Several servers.** Paper servers sharing one MySQL database see each other's punishments within half a second.
- **Safe defaults.** Permanent, silent and IP-targeted punishments each need their own permission. The console is permission-checked too. IP addresses and UUIDs are hidden in messages, and values like player names and reasons are never parsed as MiniMessage tags.
- **Five languages, every text configurable.** Pick one with `language` in `config.yml`. All messages are MiniMessage, including multi-line ban screens.
- **Painless updates.** Config files carry a `config-version`; after an update efxBans backs them up and migrates them, keeping your values.

## Installation

1. Download `efxBans-<version>.jar` from [Modrinth](https://modrinth.com/project/efxbans/) and put it in `plugins/`.
2. Start the server. `config.yml`, the `lang/` folder and the SQLite database are created in `plugins/efxBans/`.
3. Grant permissions with LuckPerms or your permission plugin. Punishment commands are enabled for operators only by default.

| Server | Java |
| --- | --- |
| Paper 1.20.6 – 1.21.11 | 21+ |
| Paper 26.1 – 26.3 | 25+ |

The plugin compiles against Paper 1.20.6 and uses the entity, region, global and async schedulers shared by Paper and Folia. Spigot and Bukkit are not supported. Other Paper forks such as Purpur should work but are not tested.

Live command, punishment and `/alts` checks passed on Paper 1.20.6 (Java 21, test clients connected through ViaVersion) and Folia 1.21.11 (Java 25, two region tick threads). Automated tests also pass against the Paper 26.3 API. MySQL verification is a separate CI job; the local checks used SQLite.

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
| `/lookup <player\|IP\|#ID>` | Active bans, IP bans and mutes in detail (alias: `/sorgu`) |
| `/banlist` · `/mutelist` | Active punishments, with clickable pages |
| `/alts <player>` | Accounts that used the same IP in the past |
| `/efxbans help\|status\|reload` | Help, status, reload settings |
| `/efxbans import <source> [confirm]` | Import from vanilla, LiteBans or AdvancedBan; see [Switching from another plugin](#switching-from-another-plugin) |

Tab completion offers online players; `/unban` offers banned players and `/unmute` muted ones, even when they are offline. Any other name can still be typed.

In `/alts`, accounts with an active ban or IP ban are red, online accounts are light green and offline accounts are light gray. A ban takes precedence over online status. With `efxbans.lookup`, clicking a red name opens that ban's details.

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
| `efxbans.history`, `efxbans.lookup`, `efxbans.banlist`, `efxbans.mutelist`, `efxbans.alts` | Viewing records (the old `efxbans.sorgu` still grants `efxbans.lookup`) |
| `efxbans.ip.view` | See the real IP in punishment details |
| `efxbans.notify` | Staff announcements |
| `efxbans.update.notify` | New version notice |
| `efxbans.reload`, `efxbans.admin` | Reload and status |
| `efxbans.import` | `/efxbans import` |
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

Short commands for punishments you give often. `/cheating Player` creates the same record as `/ipban Player 30d Cheating` and goes through the same checks.

```yaml
presets:
  cheating:
    command: cheating
    aliases: [hacks]
    type: IPBAN        # BAN, IPBAN, MUTE, KICK, WARN
    duration: 30d      # or permanent
    reason: Cheating
    permission: efxbans.preset.cheating
```

Command names may use lowercase Turkish letters (`command: küfür`); the default permission is the ASCII form, `efxbans.preset.kufur`. If you want no presets at all, write `presets: {}`.

## Languages and messages

```yaml
language: en          # lang/en.yml
timezone: UTC         # for dates in messages, e.g. Europe/Istanbul
```

| Code | Language |
| --- | --- |
| `en` | English (default) |
| `tr` | Turkish |
| `de` | German |
| `es` | Spanish |
| `fr` | French |

All bundled files are copied to `plugins/efxBans/lang/` so you can edit them; `/efxbans reload` applies changes, including a new `language`. To add a translation, copy `en.yml` to a new file such as `lang/pt_br.yml` and set `language: pt_br`. Any key you leave out falls back to English, so a partial translation still works.

Everything in the language files is [MiniMessage](https://docs.advntr.dev/minimessage/format.html). Lists of lines or YAML `|` blocks become multi-line text. The available placeholders are listed at the top of each file: `{player}`, `{staff}`, `{reason}`, `{duration}`, `{remaining}`, `{id}` and so on.

## Updating

Replace the JAR and restart. `config.yml` and the language files start with a `config-version`. When an update changes their layout, efxBans:

1. copies the old file to `plugins/efxBans/backups/` (for example `config-v1-20261024-041049.yml`),
2. moves renamed keys and applies changed defaults,
3. adds new keys with their comments, keeping every value you set.

Files from a newer efxBans are refused instead of being downgraded. Don't edit `config-version` yourself.

Coming from 1.0: your `messages.yml` becomes `lang/tr.yml` with all your changes, `language` is set to `tr`, and `/teammsg` and `/tm` are added to the muted commands.

## Discord

```yaml
discord:
  enabled: true
  url: 'https://discord.com/api/webhooks/...'
  actions: [ban, ipban, mute, kick, warn, remove, undo]
  include-ip: false
```

Discord notifications include the player's head, a localized action title, a color for the action, punishment status, expiry and the record ID. Reasons use a full-width field. Removal and undo events have their own titles and colors. Head images are loaded by Discord from MCHeads using the account UUID (or a validated player name for offline-mode accounts); address-only bans have no player image.

Only Discord webhook URLs are accepted and mentions are disabled. Errors and rate limits are retried up to three times. If a notification can't be delivered, the punishment still stands.

## Update checker

Every 12 hours efxBans checks [Modrinth](https://modrinth.com/project/efxbans/) for a newer stable release compatible with the server's Minecraft version and Paper/Folia loader. Updates are logged to the console and announced with a clickable Modrinth link to players with `efxbans.update.notify`. The check is one anonymous GET request to `api.modrinth.com`; no server or player data is sent. Alpha, beta, draft, unlisted and incompatible versions are never suggested.

```yaml
update-checker:
  enabled: true        # false: no request is made at all
  notify-staff: true
  interval-hours: 12
```

## Switching from another plugin

```text
/efxbans import vanilla              # banned-players.json and banned-ips.json
/efxbans import advancedban
/efxbans import litebans
```

The first run only reads the source and shows what it found; nothing is changed until you add `confirm`. Running it again later skips everything that was already imported, so you can import once more right before you remove the old plugin. Imported punishments keep their reasons, dates, staff names and active or lifted state, and players banned by the old plugin are kicked if they are online. Nothing is announced.

| Source | What is imported | Requirements |
| --- | --- | --- |
| `vanilla` | Bans and IP bans from `banned-players.json` and `banned-ips.json` | None |
| `advancedban` | Bans, IP bans, mutes, warnings and kicks, active and past | Its default local storage is read through AdvancedBan itself, so keep it installed and enabled until the import is done. MySQL storage needs nothing extra. |
| `litebans` | Bans, IP bans, mutes, warnings and kicks, active and past | LiteBans must use MySQL or MariaDB. Its default H2 file can't be read by efxBans; switch LiteBans to MySQL first. |

LiteBans is a paid, closed-source plugin, so its import is built from the database layout its official web interface reads and tested against tables of that shape on MySQL, not against LiteBans itself. Try it on a copy of your data first and check a few records with `/lookup` and `/history`.

The connection details are read from the other plugin's own `config.yml`. A player who already has an active efxBans punishment of the same type keeps it, and the imported one is skipped and counted. AdvancedBan notes have no counterpart and are left out; LiteBans IP mutes become mutes of their account, and LiteBans server scopes are not kept (efxBans punishments apply on every server).

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

Install efxBans on every Paper or Folia server; there is no Velocity or BungeeCord component. Keep server clocks in sync. The JDBC drivers come with the server, so keep it up to date for driver security fixes.

If the database can't be opened, or the punishment cache can't be refreshed for 15 seconds, logins are refused and public chat is paused. This is on purpose: a database outage must not let banned players back in.

## Known limitations

- An IP ban blocks one address. On IPv6, players can change their address easily.
- `/alts` shows at most 100 accounts, and a shared IP does not prove two accounts belong to the same person.
- The Discord queue is kept in memory; notifications still waiting when the server crashes are not sent.
- efxBans takes priority over commands with the same name (`/ban` goes to efxBans). The other plugin's command keeps working under its namespace, such as `/essentials:ban`.

## Development

Project-page descriptions: [Modrinth / Markdown](.github/listings/modrinth.md) · [Spigot / BBCode](.github/listings/spigot.bbcode). Both include a banner placeholder and Devin's Badges.

JDK 21+ is required. Maven is downloaded by the wrapper. Security reports: [security policy](.github/SECURITY.md).

```bash
./mvnw clean verify       # Windows: .\mvnw.cmd clean verify
```

The server JAR is `target/efxBans-<version>.jar`; `original-*.jar` is a build intermediate.

```text
src/main/java/com/efxstudios/bans/
  common/                 Database, cache, messages and Discord
  paper/                  Commands, enforcement, scheduling and integrations
src/main/resources/       Plugin metadata, configuration and five languages
src/test/java/            Unit and database contract tests
.github/                  CI, release workflow, issue templates and security policy
scripts/                  Release preparation
```

CI builds with Java 21 and 25, checks newer Paper APIs, and runs the database contracts on MySQL 8.0 and 8.4. Local MySQL tests run only when `EFXBANS_MYSQL_HOST` is set. Use a disposable `efxbans_test` database: the tests drop its tables. Set `EFXBANS_MYSQL_DATABASE`, `EFXBANS_MYSQL_USER` and `EFXBANS_MYSQL_PASSWORD` as needed, then run `./mvnw test -Dtest='MySql*Test'`.

Keep SQL values bound as parameters. Player-facing text belongs in all five language files, and user input must remain unparsed MiniMessage text. Keep database operations on `Engine` and player operations on their entity scheduler. Permission checks must run again after async lookups. Add tests and a changelog entry for behavior changes.

Configuration layout changes require a new `config-version` and migration in `Settings.steps` or `Languages.STEPS`; migration must preserve user values and back up the old file. `presets` belongs entirely to the user and must not be merged with example presets.

To prepare a release from a clean `main` branch, run `./scripts/release.ps1 -Version 1.1.0`. It dates the changelog, builds and tests, then creates a local commit and tag. Review them before pushing with `git push origin main --follow-tags`; the release workflow publishes the JAR and its SHA-256 checksum. Versions follow Semantic Versioning. A version containing a suffix such as `-beta.1` is published as a prerelease.

Publish the distribution JAR on Modrinth with its supported Minecraft versions and Paper/Folia loader tags. The in-game update checker uses listed Modrinth releases; the internal GitHub release workflow does not upload files to Modrinth.
