# Message Logger

Keeps deleted messages in the chat in red and lists what edited messages said before, like
Aliucord's MessageLogger. Logs live in memory only: they are gone after Microcord restarts, and a
kept message leaves the chat once its channel reloads.

- Settings: log deletes, log edits, ignore muted servers and channels, ignore bots, clear logs.
- Long-press a message: **Remove From Logs**.
- Server and channel menus: **Stop Logging** / **Resume Logging**.

Needs plugin API level 2.
