# Changelog

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- Update checker based on GitHub Releases. A newer version is logged to the console and announced on join, with a clickable link, to players with `efxbans.update.notify`. Turn it off with `update-checker.enabled: false`.
- `/efxbans status` now shows the plugin version.
- Usage lines, duration units, Discord field titles and the remaining hard-coded texts moved to `messages.yml` (`usages`, `texts`, `time-units`, `webhook`, `hidden`, `none`). Missing keys are added to existing files automatically.

### Changed
- The JAR shrank from 19 MB to about 290 KB: the SQLite and MySQL drivers are now loaded from Paper's own libraries.
- The distributed file is now named `efxBans-<version>.jar`.

### Security
- `/unban #ID` and `/undo #ID` now require `efxbans.unban.address` to remove an IP ban that has no account. Before, that permission was only checked when an IP was typed directly.
- Muted players could still talk in team chat with vanilla `/teammsg` and `/tm`. Both were added to the default `mute.blocked-commands`. **Existing servers** must add `teammsg, tm` to that list in their `config.yml` by hand; existing config files are not changed automatically.
- MySQL connections explicitly disable `allowLoadLocalInfile`, `allowUrlInLocalInfile` and `autoDeserialize`.

## [1.0.0] - 2026-10-22

First release: bans, IP bans, mutes, kicks and warnings; temporary and permanent punishments; presets; SQLite and MySQL; Discord webhooks; efxCore control chat integration.
