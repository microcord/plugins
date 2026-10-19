package dev.mantikafasi.manticord.translate

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import com.discord.R
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import dev.mantikafasi.manticord.plugins.api.ManticordPlugin
import dev.mantikafasi.manticord.plugins.api.PluginContextMenuContext
import dev.mantikafasi.manticord.plugins.api.PluginContextMenuSurface
import dev.mantikafasi.manticord.plugins.api.PluginScope
import dev.mantikafasi.manticord.plugins.api.pluginAsyncSlashCommand
import dev.mantikafasi.manticord.plugins.api.pluginContextMenuItem
import dev.mantikafasi.manticord.plugins.api.pluginMessageDecorator
import dev.mantikafasi.manticord.plugins.api.pluginSettings
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import rx.Completable
import rx.Observable
import rx.schedulers.Schedulers

/**
 * Translates a message in place from its long-press menu, and adds `/translate` to send translated
 * text. A port of Tyman's Aliucord Translate plugin (CC BY-NC-SA 4.0).
 */
class TranslatePlugin : ManticordPlugin {
    private lateinit var scope: PluginScope

    private class Translation(val source: String, val text: String, val from: String, val to: String) {
        @Volatile var showingOriginal = false
    }

    /** By message ID; capped, oldest first. Written on IO threads and read while binding rows. */
    private val translations = object : LinkedHashMap<Long, Translation>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Translation>) = size > 500
    }

    override val settingsSchema = pluginSettings {
        section("Translate")
        choice(
            LANGUAGE, "Translate to", default = "en", options = LANGUAGES.map { (code, name) -> code to name },
            description = "Language messages are translated into",
        )
    }

    override fun start(scope: PluginScope): Completable = Completable.fromAction {
        this.scope = scope
        val ui = scope.ui

        ui.registerContextMenuItem(pluginContextMenuItem(
            "translate", "Translate Message", PluginContextMenuSurface.MESSAGE, onClick = ::translateMessage,
        ) {
            isVisible = { context -> translation(context)?.showingOriginal != false }
        })
        ui.registerContextMenuItem(pluginContextMenuItem(
            "show_original", "Show Original", PluginContextMenuSurface.MESSAGE, onClick = { context ->
                translation(context)?.showingOriginal = true
                scope.ui.refreshMessages()
            },
        ) {
            isVisible = { context -> translation(context)?.showingOriginal == false }
        })

        ui.registerMessageDecorator(pluginMessageDecorator("translated", decorate = { views, message ->
            val translation = synchronized(translations) { translations[message.messageId] }
            // An edit invalidates the translation; the next menu tap translates the new text.
            if (translation != null && !translation.showingOriginal && translation.source == message.content) {
                val text = SpannableStringBuilder(translation.text)
                val start = text.length
                text.append("  (translated: ${translation.from} → ${translation.to})")
                text.setSpan(RelativeSizeSpan(0.75f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val muted = ColorCompat.getThemedColor(views.content.context, R.attr.colorTextMuted)
                text.setSpan(ForegroundColorSpan(muted), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                views.content.text = text
            }
        }))

        ui.registerSlashCommand(pluginAsyncSlashCommand(
            "translate", "translate", "Translates text, and sends it unless send is false",
            execute = { context, respond ->
                val arguments = context.arguments
                val result = translate(
                    arguments["text"] as String,
                    from = (arguments["from"] as String?)?.ifBlank { null } ?: "auto",
                    to = (arguments["to"] as String?)?.ifBlank { null } ?: language(),
                )
                if (arguments["send"] == false) {
                    scope.runOnMainThread { scope.dialogs.alert("Translation", result.text) }
                    respond(null)
                } else {
                    respond(result.text)
                }
            },
        ) {
            string("text", "The text to translate", required = true)
            string("to", "Language code to translate to (default: the one in settings)")
            string("from", "Language code to translate from (default: detected)")
            boolean("send", "Send the translation (default true)")
        })
    }

    private fun translation(context: PluginContextMenuContext): Translation? =
        context.messageId?.let { synchronized(translations) { translations[it] } }

    private fun translateMessage(context: PluginContextMenuContext) {
        val messageId = context.messageId ?: return
        val existing = translation(context)
        val content = context.channelId?.let { StoreStream.getMessages().getMessage(it, messageId) }?.content
        if (existing != null && existing.source == content) {
            existing.showingOriginal = false
            scope.ui.refreshMessages()
            return
        }
        if (content.isNullOrBlank()) {
            scope.dialogs.alert("Translate", "This message has no text to translate.")
            return
        }
        Observable.fromCallable { translate(content, "auto", language()) }
            .subscribeOn(Schedulers.io())
            .subscribe({ translation ->
                synchronized(translations) { translations[messageId] = translation }
                scope.ui.refreshMessages()
            }, { error ->
                scope.runOnMainThread { scope.dialogs.alert("Translate", error.message ?: "Translation failed") }
            })
    }

    private fun language() = scope.settings.getString(LANGUAGE, "en")

    /** Google Translate's free endpoint, as the original plugin uses. Blocks; call off the main thread. */
    private fun translate(text: String, from: String, to: String): Translation {
        val query = "client=gtx&dt=t&sl=${encode(from)}&tl=${encode(to)}&q=${encode(text)}"
        val connection = URL("https://translate.googleapis.com/translate_a/single?$query").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            when (val code = connection.responseCode) {
                200 -> Unit
                429 -> error("Google Translate's rate limit was reached. Try again later.")
                else -> error("Google Translate returned HTTP $code")
            }
            val json = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            val sections = json.getJSONArray(0)
            val translated = buildString {
                for (i in 0 until sections.length()) append(sections.getJSONArray(i).optString(0))
            }
            return Translation(text, translated, json.optString(2, from), to)
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val LANGUAGE = "language"
    }
}

class TranslateDiscoveryService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
