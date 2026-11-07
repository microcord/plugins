package dev.mantikafasi.manticord.lastfm

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.util.Log
import dev.mantikafasi.manticord.plugins.api.ManticordPlugin
import dev.mantikafasi.manticord.plugins.api.PluginActivityType
import dev.mantikafasi.manticord.plugins.api.PluginScope
import dev.mantikafasi.manticord.plugins.api.PluginStatusDisplay
import dev.mantikafasi.manticord.plugins.api.pluginActivity
import dev.mantikafasi.manticord.plugins.api.pluginSettings
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import rx.Completable

/**
 * Shows the track Last.fm says is playing as rich presence, with links to Gramophone, an alternative
 * Last.fm frontend. A port of bignutty and 90-008's Last.fm Rich Presence for Moonlight (GPL-3.0).
 */
class LastFmPlugin : ManticordPlugin {
    @Volatile private var scope: PluginScope? = null
    private var worker: ScheduledExecutorService? = null

    private class Track(
        val name: String,
        val album: String,
        val artist: String,
        val artistDisplayName: String,
        val url: String,
        val imageUrl: String?,
        val artistImageUrl: String?,
    )

    private class Correction(val displayName: String, val artworkUrl: String?)

    private var corrections = emptyMap<String, Correction>()
    private var correctionsRaw: String? = null

    override val settingsSchema = pluginSettings {
        section("Last.fm")
        text(USERNAME, "Username", "Your Last.fm username")
        text(
            API_KEY, "API key",
            "Optional: your own Last.fm API key (create one at https://www.last.fm/api/account/create). Leave empty to use the built-in key.",
        )
        toggle(SHARE_USERNAME, "Share username", "Show link to your Last.fm profile", default = false)
        toggle(SHARE_SONG, "Share song", "Show link to song on Last.fm", default = true)
        toggle(HIDE_WITH_SPOTIFY, "Hide if listening on Spotify", "Hide Last.fm rich presence when Spotify is playing", default = true)
        toggle(HIDE_WITH_ACTIVITY, "Hide if other activity", "Hide Last.fm rich presence if you have any other presence", default = false)
        text(STATUS_NAME, "Custom status text", "Added after Listening to / Playing", default = DEFAULT_STATUS_NAME)
        choice(
            NAME_FORMAT, "Status text format", default = FORMAT_STATUS_NAME,
            options = listOf(
                FORMAT_STATUS_NAME to "Use custom status text",
                FORMAT_ARTIST_FIRST to "Use 'artist - song' format",
                FORMAT_SONG_FIRST to "Use 'song - artist' format",
                FORMAT_ARTIST to "Use artist name only",
                FORMAT_SONG to "Use song name only",
                FORMAT_ALBUM to "Use album name (custom status text if the song has no album)",
            ),
        )
        toggle(USE_LISTENING, "Use listening status", "Show 'Listening to' status instead of 'Playing'", default = true)
        text(UPDATE_INTERVAL, "Update interval", "Seconds between checking Last.fm and updating presence (at least 5)", default = "10")
        text(
            CORRECTIONS, "Corrections data",
            "Paste the contents of your Gramophone corrections export (https://gramophone.bignut.zip/settings/corrections/manage) to apply artist corrections. Leave empty to clear.",
        )
    }

    override fun start(scope: PluginScope): Completable = Completable.fromAction {
        this.scope = scope
        worker = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "LastFmRichPresence").apply { isDaemon = true }
        }.also { it.schedule({ tick() }, 0, TimeUnit.SECONDS) }
    }

    override fun stop(): Completable = Completable.fromAction {
        worker?.shutdownNow()
        worker = null
        scope?.activities?.clearActivity(KEY)
        scope = null
    }

    private fun tick() {
        val scope = scope ?: return
        try {
            val activity = buildActivity(scope)
            if (activity == null) scope.activities.clearActivity(KEY) else scope.activities.setActivity(activity, KEY)
        } catch (e: Exception) {
            // A bad response or invalid activity leaves no stale presence behind.
            Log.w(TAG, "Failed to update presence", e)
            runCatching { scope.activities.clearActivity(KEY) }
        }
        val seconds = (scope.settings.getString(UPDATE_INTERVAL, "10").trim().toLongOrNull() ?: 10L).coerceAtLeast(MIN_INTERVAL)
        runCatching { worker?.schedule({ tick() }, seconds, TimeUnit.SECONDS) }
    }

    private fun buildActivity(scope: PluginScope): dev.mantikafasi.manticord.plugins.api.PluginActivity? {
        val settings = scope.settings
        val others = scope.activities.localActivities().filter { it.applicationId != APPLICATION_ID }
        if (settings.getBoolean(HIDE_WITH_ACTIVITY, false) && others.any { it.type != CUSTOM_STATUS }) return null
        if (settings.getBoolean(HIDE_WITH_SPOTIFY, true) && others.any { it.type == LISTENING }) return null

        val track = fetchTrack(scope) ?: return null
        val statusName = settings.getString(STATUS_NAME, DEFAULT_STATUS_NAME).ifBlank { DEFAULT_STATUS_NAME }
        val name = when (settings.getString(NAME_FORMAT, FORMAT_STATUS_NAME)) {
            FORMAT_ARTIST_FIRST -> "${track.artistDisplayName} - ${track.name}"
            FORMAT_SONG_FIRST -> "${track.name} - ${track.artistDisplayName}"
            FORMAT_ARTIST -> track.artistDisplayName
            FORMAT_SONG -> track.name
            FORMAT_ALBUM -> track.album.ifBlank { statusName }
            else -> statusName
        }

        val artist = Uri.encode(track.artist)
        val albumUrl = "$GRAMOPHONE/albums/$artist/${Uri.encode(track.album)}"
        val hasArt = !track.imageUrl.isNullOrBlank() && PLACEHOLDER_ID !in track.imageUrl
        val smallImage = track.artistImageUrl?.takeIf { it.isNotBlank() && PLACEHOLDER_ID !in it }

        return pluginActivity(name.fit()) {
            applicationId = APPLICATION_ID
            type = if (settings.getBoolean(USE_LISTENING, true)) PluginActivityType.LISTENING else PluginActivityType.PLAYING
            statusDisplay = PluginStatusDisplay.STATE
            details = track.name.fit()
            state = track.artistDisplayName.fit()
            // Without cover art the application's own placeholder assets stand in.
            largeImage = if (hasArt) track.imageUrl.image() else (if (track.album.isNotEmpty()) "album_placeholder" else "track_placeholder")
            largeText = track.album.takeIf { it.isNotEmpty() }?.fit()
            largeUrl = (if (hasArt || track.album.isNotEmpty()) albumUrl else track.url).url()
            this.smallImage = smallImage.image()
            smallText = track.artistDisplayName.fit()
            smallUrl = "$GRAMOPHONE/artists/$artist".url()
            stateUrl = "$GRAMOPHONE/artists/$artist".url()
            detailsUrl = track.url.url()
            if (settings.getBoolean(SHARE_SONG, true)) track.url.url()?.let { button("View Song", it) }
            if (settings.getBoolean(SHARE_USERNAME, false)) {
                "$GRAMOPHONE/profile/${settings.getString(USERNAME, "").trim()}".url(MAX_BUTTON_URL)?.let { button("Profile", it) }
            }
        }
    }

    private fun fetchTrack(scope: PluginScope): Track? {
        val username = scope.settings.getString(USERNAME, "").trim()
        val apiKey = scope.settings.getString(API_KEY, "").trim().ifEmpty { DEFAULT_API_KEY }
        if (username.isEmpty() || apiKey.isEmpty()) return null

        val query = listOf(
            "method" to "user.getrecenttracks", "api_key" to apiKey, "user" to username,
            "limit" to "1", "extended" to "1", "format" to "json",
        ).joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
        val connection = URL("https://ws.audioscrobbler.com/2.0/?$query").openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        val body = try {
            check(connection.responseCode in 200..299) { "Last.fm answered ${connection.responseCode}" }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }

        val json = JSONObject(body)
        if (json.has("error")) {
            Log.w(TAG, "Last.fm error ${json.opt("error")}: ${json.optString("message")}")
            return null
        }
        // A single track comes back as an object in some responses and an array in others.
        val tracks = json.optJSONObject("recenttracks")?.opt("track")
        val raw = (tracks as? JSONArray)?.optJSONObject(0) ?: tracks as? JSONObject ?: return null
        if (raw.optJSONObject("@attr")?.optString("nowplaying") != "true") return null

        val artistJson = raw.optJSONObject("artist")
        val rawArtist = artistJson?.optString("name").orEmpty().ifEmpty { "Unknown" }
        val artistImage = artistJson?.optJSONArray("image")?.optJSONObject(0)?.optString("#text").orEmpty()
        var trackImage = raw.optJSONArray("image")?.images()?.get("large").orEmpty()
        // Last.fm hands back the artist's picture for tracks without cover art.
        if (artistImage.isNotEmpty() && trackImage.contains(artistImage.substringAfterLast('/'))) trackImage = ""

        val correction = correctionFor(scope, rawArtist)
        val name = raw.optString("name").ifEmpty { "Unknown" }
        val album = raw.optJSONObject("album")?.optString("#text").orEmpty()
        return Track(
            name = name,
            album = album,
            artist = rawArtist,
            artistDisplayName = correction?.displayName ?: rawArtist,
            url = "$GRAMOPHONE/tracks/${Uri.encode(rawArtist)}/" +
                (if (album.isNotEmpty()) "${Uri.encode(album)}/" else "") + Uri.encode(name),
            imageUrl = trackImage,
            artistImageUrl = correction?.artworkUrl ?: artistImage,
        )
    }

    private fun JSONArray.images(): Map<String, String> = (0 until length()).mapNotNull { optJSONObject(it) }
        .associate { it.optString("size") to it.optString("#text") }

    private fun correctionFor(scope: PluginScope, artist: String): Correction? {
        val raw = scope.settings.getString(CORRECTIONS, "")
        if (raw != correctionsRaw) {
            correctionsRaw = raw
            corrections = parseCorrections(raw)
        }
        return corrections[artist.lowercase()]
    }

    /** Gramophone's export: either `[timestamp, artists]` or a protobuf-as-JSON `CorrectionMappings`. */
    private fun parseCorrections(raw: String): Map<String, Correction> {
        if (raw.isBlank()) return emptyMap()
        return try {
            val parsed = JSONTokener(raw).nextValue() as? JSONArray ?: return emptyMap()
            val artists = parsed.optJSONArray(1) ?: parsed.optJSONArray(0) ?: return emptyMap()
            buildMap {
                for (index in 0 until artists.length()) {
                    // Protobuf fields are 1-based array slots: key 1, display_name 3, artwork_url 4.
                    val artist = artists.optJSONArray(index) ?: continue
                    val key = artist.stringAt(0) ?: continue
                    val displayName = artist.stringAt(2) ?: continue
                    put(key.lowercase(), Correction(displayName, artist.stringAt(3)?.let(::artworkUrl)))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse corrections data", e)
            emptyMap()
        }
    }

    private fun JSONArray.stringAt(index: Int): String? = if (isNull(index)) null else optString(index)

    private fun artworkUrl(path: String) =
        if ("/" in path) path else "https://lastfm.freetls.fastly.net/i/u/300x300/$path.png"

    /** Discord's activity limits: lines of 2 to 128 characters. A single character is padded to fit. */
    private fun String.fit() = take(MAX_TEXT).let { if (it.length < 2) it + "​" else it }

    private fun String?.url(max: Int = MAX_URL) = this?.takeIf { it.length <= max }
    private fun String?.image() = this?.takeIf { it.length <= MAX_IMAGE }

    private companion object {
        const val TAG = "LastFmRichPresence"
        const val KEY = "lastfm"
        const val APPLICATION_ID = 1392966386011340871L

        /**
         * The key used when the user enters none. A read-only Last.fm API key identifies this plugin's
         * requests, not any user; it grants no access to anyone's account.
         */
        const val DEFAULT_API_KEY = "16b0a55a27328eca21c1bfff840d9043"
        const val PLACEHOLDER_ID = "2a96cbd8b46e442fc41c2b86b821562f"
        const val GRAMOPHONE = "https://gramophone.bignut.zip"
        const val TIMEOUT_MS = 10_000
        const val MIN_INTERVAL = 5L
        const val MAX_TEXT = 128
        const val MAX_URL = 256
        const val MAX_BUTTON_URL = 512
        const val MAX_IMAGE = 300
        const val LISTENING = 2
        const val CUSTOM_STATUS = 4

        const val USERNAME = "username"
        const val API_KEY = "apiKey"
        const val SHARE_USERNAME = "shareUsername"
        const val SHARE_SONG = "shareSong"
        const val HIDE_WITH_SPOTIFY = "hideWithSpotify"
        const val HIDE_WITH_ACTIVITY = "hideWithActivity"
        const val STATUS_NAME = "statusName"
        const val NAME_FORMAT = "nameFormat"
        const val USE_LISTENING = "useListeningStatus"
        const val UPDATE_INTERVAL = "updateInterval"
        const val CORRECTIONS = "correctionsData"

        const val DEFAULT_STATUS_NAME = "some music"
        const val FORMAT_STATUS_NAME = "status-name"
        const val FORMAT_ARTIST_FIRST = "artist-first"
        const val FORMAT_SONG_FIRST = "song-first"
        const val FORMAT_ARTIST = "artist"
        const val FORMAT_SONG = "song"
        const val FORMAT_ALBUM = "album"
    }
}

class LastFmDiscoveryService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
