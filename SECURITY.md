# Security policy

## Supported versions

Security fixes go into the latest release only. If you run an older version, update first.

## Reporting a vulnerability

**Please don't open a public issue for security problems.** Use GitHub's private reporting form instead:
**Security → Report a vulnerability** ([direct link](https://github.com/efxstudios/efxBans/security/advisories/new)).

Please include:

- the affected version, the Paper version and the database type,
- the steps or commands that trigger it,
- who can do what, for example "someone with only `efxbans.unban` can remove an IP ban".

We aim to reply within 7 days. Please keep the details private until a fix is released; reporters are credited in the release notes if they want to be.

## Scope

In scope: permission bypasses, punishment bypasses (ban or mute evasion), SQL or MiniMessage injection, leaks of data that should stay hidden (IPs, UUIDs), and exposure of the webhook URL or the database password.

Out of scope: things a server owner can already do with permissions they granted (an operator banning everyone, for example), and vulnerabilities in Paper or the JDBC drivers themselves. Please report those to the project concerned. The SQLite and MySQL drivers ship with Paper, so keep Paper up to date.
