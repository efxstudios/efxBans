# Changelog

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- Languages: English, Turkish, German, Spanish and French in `plugins/efxBans/lang/<code>.yml`, selected with `language` in `config.yml`. Custom files such as `lang/pt_br.yml` work too; missing keys fall back to English.
- Config versioning: `config.yml` and the language files start with `config-version`. After an update, outdated files are backed up to `backups/`, migrated (renamed keys, changed defaults) and completed with new keys and their comments, keeping existing values. Files from a newer version are refused.
- `timezone` and `date-format` settings for dates in messages.
- Update checker based on GitHub Releases. A newer version is logged to the console and announced on join, with a clickable link, to players with `efxbans.update.notify`. Turn it off with `update-checker.enabled: false`.
- `/efxbans status` now shows the plugin version.
- Usage lines, duration units (singular and plural), Discord field titles and the remaining hard-coded texts moved to the language files.

### Changed
- The default language is English. Servers upgrading from 1.0 keep Turkish: `messages.yml` becomes `lang/tr.yml` with all customizations and `language` is set to `tr`.
- `/sorgu` is now `/lookup` with the permission `efxbans.lookup`. `/sorgu` keeps working as an alias, and `efxbans.sorgu` still grants `efxbans.lookup`.
- New installs get English example presets (`cheating`, `advertising`, `swearing`, `spam`); existing presets are not touched.
- Console log messages are in English.
- The JAR shrank from 19 MB to about 290 KB: the SQLite and MySQL drivers are now loaded from Paper's own libraries.
- The distributed file is now named `efxBans-<version>.jar`.

### Security
- `/unban #ID` and `/undo #ID` now require `efxbans.unban.address` to remove an IP ban that has no account. Before, that permission was only checked when an IP was typed directly.
- Muted players could still talk in team chat with vanilla `/teammsg` and `/tm`. Both are now in the default `mute.blocked-commands`, and the config migration adds them to existing lists automatically.
- The `language` value is restricted to plain file names, so it can't point outside `lang/`.
- MySQL connections explicitly disable `allowLoadLocalInfile`, `allowUrlInLocalInfile` and `autoDeserialize`.

## [1.0.0] - 2026-10-22

First release: bans, IP bans, mutes, kicks and warnings; temporary and permanent punishments; presets; SQLite and MySQL; Discord webhooks; efxCore control chat integration.
