# Contributing

## Building

JDK 21 or newer is all you need. Maven doesn't have to be installed; the repository includes the Maven Wrapper.

The build compiles against the oldest supported Paper API (1.20.6, with the Adventure version it ships), so code can't accidentally use something older servers lack. Dependabot is told not to raise those two. CI also builds and tests against Paper 1.21.11 and 26.3.

```bash
./mvnw verify          # Linux / macOS
.\mvnw.cmd verify      # Windows
```

The JAR for the server is `paper/target/efxBans-<version>.jar`. Don't put `original-*.jar` or the `common` module's JAR on a server.

To check against a newer Paper API, e.g. 26.x (needs JDK 25):

```bash
./mvnw verify -Dpaper.version=26.3.build.214-stable -Dmaven.compiler.release=25
```

### MySQL tests

The store tests always run on SQLite. The same tests also run against a real MySQL server when the environment variables are set. **Every test drops the tables of that database, so never point this at real data.**

```bash
EFXBANS_MYSQL_HOST=127.0.0.1 EFXBANS_MYSQL_USER=root EFXBANS_MYSQL_PASSWORD= \
EFXBANS_MYSQL_DATABASE=efxbans_test ./mvnw -pl common -am test -Dtest=MySqlStoreTest -Dsurefire.failIfNoSpecifiedTests=false
```

CI runs them on MySQL 8.0 and 8.4.

## Project layout

| Module | Contents |
| --- | --- |
| `common` | Core with no Paper dependency: database (`Store`), work queue (`Engine`), cache, message rendering, Discord, version comparison |
| `paper` | The Paper plugin: commands (`CommandTree`), login and chat enforcement (`Enforcement`), permissions, settings, update checker |

## Rules

- **SQL:** Values are always bound as `?` parameters. Only constants from the code may be concatenated into SQL.
- **Messages:** Text that players see belongs in the language files in `paper/src/main/resources/lang/`, not in code. Add a new key to **all five** files (`SettingsTest` fails if a language is missing a key that English has). Values such as player names and reasons are inserted through `Messages.render`, which never parses them as MiniMessage. Console logs are English and stay in code.
- **Threads:** JDBC runs only on the `Engine` thread; the Bukkit API is called only on the main thread (`plugin.main(...)`).
- **Permissions:** Commands check permissions again when they run. `requires` only hides a command.
- Every change in behaviour comes with a test. Code style is in `.editorconfig`: 4-space indentation, LF line endings.

## Config versions

`config.yml` and every language file start with `config-version`. It is separate from the plugin version and only changes when the file's layout changes. On startup, `ConfigFile` compares the file on disk with the bundled template:

- **Same version:** the file is used as it is.
- **Older:** the file is copied to `backups/`, the migration steps run one version at a time, and the file is rebuilt from the bundled template with the user's values on top. New keys arrive with their comments.
- **Newer:** loading fails; efxBans never downgrades a file.

When you change the layout of `config.yml`:

1. Raise `config-version` in `paper/src/main/resources/config.yml` and `Settings.CONFIG_VERSION`.
2. If keys are only **added**, you're done: the rebuild adds them.
3. If keys are **renamed, removed or need a new value**, add a step to `Settings.steps(...)`:
   ```java
   new ConfigFile.Step(2, "discord.url renamed to discord.webhook", c -> ConfigFile.rename(c, "discord.url", "discord.webhook"))
   ```
   Step `N` turns version `N` into `N + 1`. Use `ConfigFile.rename`, `ConfigFile.addToList` or plain `c.set(...)`.
4. Add a case to `SettingsTest` that loads the old layout.

Language files work the same way, with their own `config-version` and `Languages.STEPS`. Sections that belong entirely to the user, such as `presets`, are listed in `Settings.USER_OWNED`: they are copied whole and never mixed with the template's examples.

## Versions

Version numbers follow [Semantic Versioning](https://semver.org/):

| Change | Example | When |
| --- | --- | --- |
| **MAJOR** | 1.4.2 → 2.0.0 | Incompatible change a server owner has to act on: removed config keys, database schema, renamed commands or permissions |
| **MINOR** | 1.4.2 → 1.5.0 | New, backwards-compatible feature |
| **PATCH** | 1.4.2 → 1.4.3 | Bug fixes only |

Pre-releases look like `2.0.0-beta.1`. They are marked as pre-releases on GitHub, and the update checker never suggests them.

The version is stored in exactly one place: `<revision>` in the root `pom.xml`. `paper-plugin.yml` and the version the plugin reports are generated from it at build time.

### Cutting a release

1. Describe the changes under `## [Unreleased]` in `CHANGELOG.md`.
2. On `main`, with a clean working tree:
   ```powershell
   ./scripts/release.ps1 -Version 1.2.0
   ```
   The script sets `<revision>`, moves the `[Unreleased]` notes under `[1.2.0] - <date>`, runs the full test suite, commits `Release 1.2.0` and creates the tag `v1.2.0`. It pushes nothing.
3. Publish:
   ```bash
   git push origin main --follow-tags
   ```
   `release.yml` checks that the tag matches the version in `pom.xml`, runs the tests again and creates the GitHub release with the JAR and `SHA256SUMS.txt`. The release notes come from the changelog.

Servers check the `releases/latest` endpoint, so servers with the update checker enabled see a new version as soon as it is published.
