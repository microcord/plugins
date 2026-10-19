# Microcord Plugins

Official plugins for [Microcord](https://github.com/microcord/microcord), listed in its in-app plugin
browser.

| Plugin | What it does |
| --- | --- |
| [Message Logger](messagelogger) | Keeps deleted messages in the chat and shows edit history |
| [ReviewDB](reviewdb) | User and server reviews |
| [Translate](translate) | Translates messages and sends translated text |

## Building

Plugins compile against the Microcord plugin SDK from Microcord's Maven. Put your `git.hoodnet.work`
username and a token with `read:packages` in `~/.gradle/gradle.properties` as `gpr.user` and `gpr.key`.

```bash
./gradlew :messagelogger:assembleDebug
```

## Releases

Every push to `main` runs [the release workflow](.github/workflows/release.yml). It builds the plugins
whose `versionCode` changed, signs them with the shared plugin key and commits them to the `builds`
branch together with `plugins.json`, the index Microcord's plugin browser reads through jsDelivr:
`https://cdn.jsdelivr.net/gh/microcord/plugins@builds/plugins.json`. To release a plugin, bump its
`versionCode`.

## Third-party plugins

Plugins built elsewhere are listed by adding an entry to [third-party.json](third-party.json) in a pull
request: the same fields as an entry in `plugins.json` (`id`, `packageName`, `name`, `description`,
`authors`, `versionCode`, `versionName`, `pluginApiMinimum`, `apkUrl`, `sha256`, `size`,
`signerSha256`, `sourceUrl`). `apkUrl` must be a permanent HTTPS link, such as a GitHub release asset;
the app refuses an APK whose SHA-256 or signer differs from the entry.

## Adding a plugin

Copy an existing module, give it a new `applicationId` and plugin `id`, set `pluginName` and
`pluginAuthors` in its `gradle.properties`, and add it to `settings.gradle.kts`. See the
[plugin API documentation](https://github.com/microcord/microcord/blob/master/docs/plugin-api.md).
