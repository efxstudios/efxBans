# Changelog

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Paper 1.16.5–26.3 compatibility in one Java 16 bytecode JAR, with legacy commands and scheduling, isolated modern adapters, and private Adventure/Gson libraries.
- Discord embeds now show player heads, localized action titles, action colors, expiry and the punishment ID; reasons occupy a full-width field. Heads are automatic for player targets and are omitted for address-only bans.
- Folia support: player actions and command callbacks follow their owning regions; background work uses the async scheduler.
- `/alts` shows banned accounts in red with permission-checked ban lookup links, online accounts in light green and offline accounts in light gray.
- `/efxbans import <vanilla|litebans|advancedban> [confirm]`: imports bans, IP bans, mutes, warnings and kicks from the vanilla ban lists, LiteBans (MySQL/MariaDB) and AdvancedBan (local storage or MySQL). It previews first, keeps dates, reasons, staff names and lifted/expired state, and can be run again safely: already imported records are skipped. Permission `efxbans.import`.
- Languages: English, Turkish, German, Spanish and French in `plugins/efxBans/lang/<code>.yml`, selected with `language` in `config.yml`. Custom files such as `lang/pt_br.yml` work too; missing keys fall back to English.
- Config versioning: `config.yml` and the language files start with `config-version`. After an update, outdated files are backed up to `backups/`, migrated (renamed keys, changed defaults) and completed with new keys and their comments, keeping existing values. Files from a newer version are refused.
- `timezone` and `date-format` settings for dates in messages.
- Update checker based on GitHub Releases. A newer version is logged to the console and announced on join, with a clickable link, to players with `efxbans.update.notify`. Turn it off with `update-checker.enabled: false`.
- `/efxbans status` now shows the plugin version.
- Tab completion suggests online players. `/unban` suggests banned players and `/unmute` muted players, online or not; the names come from memory, so typing never queries the database.
- Usage lines, duration units (singular and plural), Discord field titles and the remaining hard-coded texts moved to the language files.

### Changed

- Removed code, script and language-file comments; kept configuration comments and only the preset permission summary above presets. Plugin version stays 1.0.0 and config-version is unchanged.
- Update checks and download notices now use the Modrinth project page; only published stable versions compatible with the running Minecraft version and Paper/Folia loader are suggested.
- efxCore control chat compatibility is maintained only on the `restamc` branch; the main plugin enforces mutes without an efxCore dependency or integration setting.
- Standard single-module Maven layout (`src/main`, `src/test`, `target`); development instructions are consolidated in README and the security policy lives in `.github`.
- The default prefix no longer uses the `<shadow>` tag, which servers before 1.21.4 would show as plain text.
- The default language is English. Servers upgrading from 1.0 keep Turkish: `messages.yml` becomes `lang/tr.yml` with all customizations and `language` is set to `tr`.
- `/sorgu` is now `/lookup` with the permission `efxbans.lookup`. `/sorgu` keeps working as an alias, and `efxbans.sorgu` still grants `efxbans.lookup`.
- New installs get English example presets (`cheating`, `advertising`, `swearing`, `spam`); existing presets are not touched.
- Console log messages are in English.
- The database query for recently seen names (every 30 seconds) is gone; it was only used for suggestions.
- List and lookup headers say "Total: 1" instead of "1 records".
- The JAR shrank from 19 MB to about 350 KB: the SQLite and MySQL drivers are now loaded from the server's own libraries.
- The distributed file is now named `efxBans-<version>.jar`.

### Security
- `/unban #ID` and `/undo #ID` now require `efxbans.unban.address` to remove an IP ban that has no account. Before, that permission was only checked when an IP was typed directly.
- Muted players could still talk in team chat with vanilla `/teammsg` and `/tm`. Both are now in the default `mute.blocked-commands`, and the config migration adds them to existing lists automatically.
- The `language` value is restricted to plain file names, so it can't point outside `lang/`.
- MySQL connections explicitly disable `allowLoadLocalInfile`, `allowUrlInLocalInfile` and `autoDeserialize`.

## [1.0.0] - 2026-10-22

First release: bans, IP bans, mutes, kicks and warnings; temporary and permanent punishments; presets; SQLite and MySQL; Discord webhooks; efxCore control chat integration.
