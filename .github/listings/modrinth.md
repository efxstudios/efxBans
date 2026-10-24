![efxBans banner](BANNER_URL)

# efxBans

An **advanced**, **powerful**, and **customizable** punishment plugin for **Minecraft** servers.

[![Paper](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/paper_64h.png)](https://papermc.io/)
[![Available on Modrinth](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/modrinth_64h.png)](https://modrinth.com/project/efxbans/)
![Java](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/built-with/java_64h.png)
![Won’t support Spigot](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/unsupported/spigot_64h.png)
[![Chat with us on Discord](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/social/discord-plural_64h.png)](https://discord.gg/efxstudios)

Give your staff one place to manage bans, IP bans, mutes, kicks and warnings. Start with SQLite, share punishments across servers with MySQL, and make the messages fit your community.

**Version 1.0.0 · Paper 1.16.5–26.3 · Folia · SQLite & MySQL · Five languages**

## Compatibility

Use the same efxBans JAR across the supported Minecraft versions, with the Java version required by your server.

| Minecraft / Paper | Java |
| --- | --- |
| 1.16.5 | 16 |
| 1.17–1.19.4 | 17 |
| 1.20–1.21.11 | 21+ |
| 26.1–26.3 | 25+ |

**Folia is supported** on its available server versions. Use the Java version required by that Folia build.

Paper or Folia server software is required. Spigot and Bukkit server software are not supported.

## Moderation essentials

- **Temporary and permanent punishments.** Ban, IP ban or mute an account with a reason and a duration. Kick online players or issue warnings to online and offline accounts.
- **History you can follow.** Every punishment has its own ID. View an account's history, inspect active punishments, remove a restriction or undo a specific record.
- **Quick staff workflows.** Online player suggestions, banned-player suggestions for `/unban`, muted-player suggestions for `/unmute`, and clickable active lists.
- **Silent actions.** Use `-s` for a ban, IP ban, mute or kick without a server announcement. Discord notifications still follow your settings.

```text
/ban Player 7d Cheating
/mute Player 30m Chat spam
/kick -s Player Please read the rules
/warn Player Keep the chat respectful
/lookup Player
```

Durations use `s`, `m`, `h`, `d` and `w`. Combine them as needed: `1d12h`, `2h30m` or `7d`. Omit the duration for a permanent ban or mute when your permissions allow it.

## Related accounts at a glance

Use `/alts <player>` to find accounts that previously shared an IP address.

- **Red:** an active ban or IP ban. Click the name to inspect that ban with `/lookup`.
- **Light green:** online, with no active ban.
- **Light gray:** offline, with no active ban.

Ban lookup links require `efxbans.lookup`. Shared IP addresses are a moderation clue; they do not establish that two accounts belong to the same person.

## Make it yours

**Five included languages:** English, Turkish, German, Spanish and French. Edit the language files, create your own translation, and customize messages and ban screens with MiniMessage.

**Custom punishment commands:** create presets for common situations. The included examples give you `/cheating Player` for a 30-day IP ban, `/advertising Player` for a 7-day mute, `/swearing Player` for a 2-hour mute and `/spam Player` for a warning. Change their commands, aliases, reasons, durations and permissions in `config.yml`.

A preset needs its configured permission, the corresponding punishment permission, temporary or permanent access for timed punishments, and an allowed duration.

**Permissions for your staff:** control temporary, permanent, silent and IP-targeted actions separately. Set duration limits and account exemptions for your staff setup.

## Discord webhooks

Send moderation events to a Discord channel with embeds that include:

- **Automatic player heads:** an avatar beside the player name and a larger head thumbnail for player targets. Address-only punishments omit player images.
- **Action titles and colors:** localized labels distinguish bans, IP bans, mutes, kicks, warnings, removals and undo actions.
- **Useful context:** player, staff member, punishment type, reason and the punishment ID.
- **Timed punishment details:** duration and expiry, with Discord dates and relative timestamps for temporary punishments.

Choose the events to send in `discord.actions`. IP addresses are excluded by default and can be included with `discord.include-ip`. Player heads appear automatically; no extra setting or plugin is required.

## One server or several

**SQLite** works out of the box for a single server. **MySQL** lets multiple Paper or Folia servers share punishment records and active restrictions. Install efxBans on each server and connect them to the same database.

If the database cannot be verified, new logins are refused and chat is blocked once the punishment cache becomes stale. Existing punishment records remain in the database.

## Switching from another ban plugin

Import from **vanilla ban lists**, **AdvancedBan** or **LiteBans**:

```text
/efxbans import vanilla
/efxbans import advancedban
/efxbans import litebans
```

The command previews the source first. Add `confirm` to import it. Existing imports are skipped when you run it again, and imported records keep their reasons, staff names, dates and active or lifted state.

LiteBans requires MySQL or MariaDB storage. AdvancedBan supports MySQL and its default local storage; keep AdvancedBan enabled when importing from its local database.

## Getting started

1. Choose a supported **Paper** or **Folia** build and the Java version listed above.
2. Download **efxBans 1.0.0** from [Modrinth](https://modrinth.com/project/efxbans/).
3. Put `efxBans-1.0.0.jar` in your server's `plugins` folder and restart.
4. Choose your language in `plugins/efxBans/config.yml` and assign staff permissions.

SQLite is enabled by default. No other plugin is required.

Run `/efxbans reload` after editing messages and most settings. Changes to the database, command names or aliases require a restart. Outdated configuration files are backed up before migration.

## Updates

The optional update checker uses [Modrinth](https://modrinth.com/project/efxbans/) and only suggests published stable releases compatible with your Minecraft version and Paper or Folia loader. Staff with `efxbans.update.notify` receive a clickable download notice when they join. Disable checks with `update-checker.enabled: false`.

## Command reference

| Command | Purpose |
| --- | --- |
| `/ban`, `/ipban`, `/mute` | Temporary or permanent restrictions |
| `/kick`, `/warn` | Kicks and warnings |
| `/unban`, `/unmute` | Remove active restrictions |
| `/undo <#ID>` | Revoke one punishment record |
| `/lookup <player\|IP\|#ID>` | Inspect active bans, IP bans and mutes |
| `/history <player> [page]` | View punishment history |
| `/punishment <#ID>` | Inspect a record and its audit history |
| `/banlist`, `/mutelist` | Browse active restrictions |
| `/alts <player>` | Find accounts that shared an IP |
| `/efxbans help` | Show available commands |
| `/efxbans status` | Show version and database status |
| `/efxbans reload` | Reload messages and settings |
| `/efxbans import <source> [confirm]` | Preview or import another plugin's records |

## Permissions

Temporary and permanent permissions are separate. For example, a temporary account ban needs both `efxbans.ban` and `efxbans.ban.temporary`, and its duration must fit the staff member's limit. Silent actions also need the matching `.silent` permission.

| Permission | Access |
| --- | --- |
| `efxbans.ban` | Use /ban; also grant its temporary or permanent node. |
| `efxbans.ban.temporary` | Issue temporary account bans. |
| `efxbans.ban.permanent` | Issue permanent account bans. |
| `efxbans.ipban` | Use /ipban; also grant its temporary or permanent node. |
| `efxbans.ipban.temporary` | Issue temporary IP bans. |
| `efxbans.ipban.permanent` | Issue permanent IP bans. |
| `efxbans.ipban.address` | Target an IP address directly; requires the normal IP ban permissions too. |
| `efxbans.mute` | Use /mute; also grant its temporary or permanent node. |
| `efxbans.mute.temporary` | Issue temporary mutes. |
| `efxbans.mute.permanent` | Issue permanent mutes. |
| `efxbans.kick` | Kick online players. |
| `efxbans.warn` | Warn online or offline players. |
| `efxbans.ban.silent` · `efxbans.ipban.silent` · `efxbans.mute.silent` · `efxbans.kick.silent` | Use -s for the corresponding action; the normal punishment permissions are still required. |
| `efxbans.unban` | Remove account bans and IP bans. |
| `efxbans.unban.address` | Remove a ban by IP, or an address-only IP ban by ID, including through /undo. |
| `efxbans.unmute` | Remove active mutes. |
| `efxbans.undo` | Revoke one punishment by ID. |
| `efxbans.history` | Use /history and /punishment. |
| `efxbans.lookup` | Inspect active punishments; enables ban links in /alts. |
| `efxbans.sorgu` | Legacy permission alias that also grants efxbans.lookup. |
| `efxbans.banlist` · `efxbans.mutelist` | Browse the corresponding active list. |
| `efxbans.alts` | Find accounts that shared an IP address. |
| `efxbans.ip.view` | See IP addresses in punishment details. |
| `efxbans.notify` | Receive staff punishment announcements. |
| `efxbans.update.notify` | Receive new-version notifications. |
| `efxbans.reload` | Reload settings and messages. |
| `efxbans.admin` | View /efxbans status; does not grant every staff permission. |
| `efxbans.import` | Preview and confirm imports. |
| `efxbans.preset.<name>` | Use the matching preset. Its punishment permissions and duration limit still apply; custom preset nodes can be set in config.yml. |
| `efxbans.exempt.<kind>` · `efxbans.exempt.all` | Protect an account from a kind of punishment, or all kinds. Kinds: ban, ipban, mute, kick, warn. |
| `efxbans.bypass.exempt` | Punish exempt accounts; normal punishment permissions still apply. |
| `efxbans.duration.unlimited` | Remove the duration cap only; does not grant punishment commands. |

Staff nodes listed above default to operators, except exemption, exemption-bypass and unlimited-duration nodes, which default to false. Preset permissions are configured per preset. Use your permission plugin to assign the nodes your staff need. Temporary punishments are capped at 30 days by default; `permissions.duration-limits` in `config.yml` can raise that limit per permission.

Need help? [Chat with us on Discord](https://discord.gg/efxstudios).
