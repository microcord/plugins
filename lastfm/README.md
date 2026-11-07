# Last.fm Rich Presence

Port of [bignutty and 90-008's Last.fm Rich Presence](https://github.com/bignutty/moonlight-extensions/tree/main/src/lastfmrpc)
for Moonlight.

Shows the track Last.fm reports as now playing as a rich presence, with links to
[Gramophone](https://gramophone.bignut.zip), an alternative Last.fm frontend. It needs your Last.fm
username. It can use your own [API key](https://www.last.fm/api/account/create) (set it in the
settings) or the plugin's built-in one (`DEFAULT_API_KEY` in `LastFmPlugin.kt`).

Settings: share the song or profile link as buttons, hide when Spotify or another activity is showing,
the status text format, "Listening to" or "Playing", the update interval (at least 5 seconds) and an
optional Gramophone corrections export for artist names and artwork.

Uses the plugin activities API (plugin API level 3), so it needs a Microcord build with it.

Differences from the original: a custom status does not count as an "other activity" for the hide
option, and an unfetchable or invalid track clears the presence rather than keeping the last one.

## License

Adapted from the original, which is licensed under [GPL-3.0](https://www.gnu.org/licenses/gpl-3.0.html);
this port is too.
