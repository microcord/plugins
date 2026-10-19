package dev.mantikafasi.manticord.messagelogger

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import com.discord.R
import com.discord.models.domain.ModelMessageDelete
import com.discord.models.message.Message
import com.discord.stores.StoreStream
import com.discord.utilities.channel.ChannelNotificationSettingsUtils
import com.discord.utilities.color.ColorCompat
import dev.mantikafasi.manticord.plugins.api.ManticordPlugin
import dev.mantikafasi.manticord.plugins.api.PluginContextMenuContext
import dev.mantikafasi.manticord.plugins.api.PluginContextMenuSurface
import dev.mantikafasi.manticord.plugins.api.PluginMessagePlacement
import dev.mantikafasi.manticord.plugins.api.PluginScope
import dev.mantikafasi.manticord.plugins.api.pluginContextMenuItem
import dev.mantikafasi.manticord.plugins.api.pluginMessageContribution
import dev.mantikafasi.manticord.plugins.api.pluginMessageDecorator
import dev.mantikafasi.manticord.plugins.api.pluginMessageDeleteInterceptor
import dev.mantikafasi.manticord.plugins.api.pluginMessageUpdateInterceptor
import dev.mantikafasi.manticord.plugins.api.pluginSettings
import rx.Completable

/**
 * Keeps deleted messages in the chat, in red, and lists what each edited message said before.
 *
 * Nothing is written to disk: the log lasts until Microcord restarts, and a kept message disappears
 * once its channel is reloaded from Discord.
 */
class MessageLoggerPlugin : ManticordPlugin {
    private lateinit var scope: PluginScope
    private val log = MessageLog()

    override val settingsSchema = pluginSettings {
        section("Logging")
        toggle(LOG_DELETES, "Log deleted messages", "Keep deleted messages in the chat", default = true)
        toggle(LOG_EDITS, "Log edits", "Show what edited messages said before", default = true)
        toggle(IGNORE_MUTED, "Ignore muted servers and channels", default = true)
        toggle(IGNORE_BOTS, "Ignore bots", default = false)
        section("Logs")
        action("clear_logs", "Clear logs", "Forget every logged edit and remove kept deleted messages") {
            scope.dialogs.confirm("Clear logs?", "Kept deleted messages leave the chat.", "Clear") {
                log.clear().forEach { (messageId, channelId) -> deleteFromChat(channelId, messageId) }
                scope.ui.refreshMessages()
            }
        }
        action("clear_ignored", "Log everywhere again", "Forget the servers and channels logging was stopped in") {
            scope.settings.putString(IGNORED_GUILDS, "")
            scope.settings.putString(IGNORED_CHANNELS, "")
        }
    }

    override fun start(scope: PluginScope): Completable = Completable.fromAction {
        this.scope = scope
        val ui = scope.ui

        ui.registerMessageInterceptor(pluginMessageDeleteInterceptor("keep_deleted", onDelete = { message ->
            message.takeIf { scope.settings.getBoolean(LOG_DELETES, true) && isLogged(it) }
                ?.also { log.markDeleted(it.id, it.channelId, System.currentTimeMillis()) }
        }))

        ui.registerMessageInterceptor(pluginMessageUpdateInterceptor("log_edits", onUpdate = { old, new ->
            val previous = old.content
            if (!previous.isNullOrBlank() && previous != new.content && scope.settings.getBoolean(LOG_EDITS, true) && isLogged(old)) {
                log.addEdit(old.id, old.channelId, previous, System.currentTimeMillis())
            }
            new
        }))

        ui.registerMessageDecorator(pluginMessageDecorator("deleted_red", decorate = { views, message ->
            if (log.isDeleted(message.messageId)) views.content.setTextColor(DELETED_COLOR)
        }))

        ui.registerMessage(pluginMessageContribution(
            "history", "Message history", PluginMessagePlacement.CONTENT, ::createHistoryView,
        ) {
            isVisible = { message -> log.get(message.messageId) != null }
            bind = { view, message -> log.get(message.messageId)?.let { bindHistory(view as TextView, it) } }
        })

        registerMenus()
    }

    override fun stop(): Completable = Completable.fromAction {
        // Kept messages stay until their channel reloads; their decoration goes with the plugin.
        log.clear()
    }

    private fun registerMenus() {
        val ui = scope.ui
        ui.registerContextMenuItem(pluginContextMenuItem(
            "remove_from_logs", "Remove From Logs", PluginContextMenuSurface.MESSAGE, onClick = ::removeFromLogs,
        ) {
            isVisible = { context -> context.messageId?.let(log::get) != null }
        })
        ignoreToggle("server", "Server", PluginContextMenuSurface.GUILD, IGNORED_GUILDS) { it.guildId }
        ignoreToggle("channel", "Channel", PluginContextMenuSurface.CHANNEL, IGNORED_CHANNELS) { it.channelId }
    }

    /** A "Stop Logging" item and a "Resume Logging" item, only one of which shows at a time. */
    private fun ignoreToggle(
        id: String,
        noun: String,
        surface: PluginContextMenuSurface,
        key: String,
        targetOf: (PluginContextMenuContext) -> Long?,
    ) {
        fun ignored(context: PluginContextMenuContext) = targetOf(context)?.let { it in ids(key) }
        scope.ui.registerContextMenuItem(pluginContextMenuItem(
            "stop_$id", "Stop Logging $noun", surface,
            onClick = { context -> targetOf(context)?.let { setIds(key, ids(key) + it) } },
        ) { isVisible = { ignored(it) == false } })
        scope.ui.registerContextMenuItem(pluginContextMenuItem(
            "resume_$id", "Resume Logging $noun", surface,
            onClick = { context -> targetOf(context)?.let { setIds(key, ids(key) - it) } },
        ) { isVisible = { ignored(it) == true } })
    }

    private fun removeFromLogs(context: PluginContextMenuContext) {
        val messageId = context.messageId ?: return
        val record = log.remove(messageId) ?: return
        if (record.deletedAt != null) deleteFromChat(record.channelId, messageId)
        scope.ui.refreshMessages()
    }

    /** Re-runs Discord's delete for a kept message; the log no longer has it, so it goes through. */
    private fun deleteFromChat(channelId: Long, messageId: Long) {
        StoreStream.getGatewayConnection().messageDelete.onNext(ModelMessageDelete(channelId, messageId))
    }

    private fun isLogged(message: Message): Boolean {
        if (scope.settings.getBoolean(IGNORE_BOTS, false) && message.author?.bot == true) return false
        if (message.channelId in ids(IGNORED_CHANNELS)) return false
        val channel = StoreStream.getChannels().getChannel(message.channelId) ?: return true
        if (channel.guildId in ids(IGNORED_GUILDS)) return false
        if (!scope.settings.getBoolean(IGNORE_MUTED, true)) return true
        val settings = StoreStream.getUserGuildSettings().getGuildSettings()[channel.guildId]
        return !ChannelNotificationSettingsUtils.isGuildCategoryOrChannelMuted(channel, settings)
    }

    private fun ids(key: String): Set<Long> =
        scope.settings.getString(key).split(',').mapNotNullTo(HashSet()) { it.toLongOrNull() }

    private fun setIds(key: String, ids: Set<Long>) = scope.settings.putString(key, ids.joinToString(","))

    private fun createHistoryView(context: Context): View = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(ColorCompat.getThemedColor(context, R.attr.colorTextMuted))
    }

    private fun bindHistory(view: TextView, record: MessageLog.Record) {
        val now = System.currentTimeMillis()
        val text = SpannableStringBuilder()
        record.edits.forEach { edit ->
            if (text.isNotEmpty()) text.append('\n')
            val start = text.length
            text.append(edit.content)
            text.setSpan(StrikethroughSpan(), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.append("  (edited ").append(time(edit.editedAt, now)).append(')')
        }
        record.deletedAt?.let { deletedAt ->
            if (text.isNotEmpty()) text.append('\n')
            val start = text.length
            text.append("(deleted ").append(time(deletedAt, now)).append(')')
            text.setSpan(ForegroundColorSpan(DELETED_COLOR), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        view.text = text
    }

    private fun time(at: Long, now: Long): CharSequence =
        DateUtils.formatSameDayTime(at, now, java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)

    private companion object {
        const val LOG_DELETES = "log_deletes"
        const val LOG_EDITS = "log_edits"
        const val IGNORE_MUTED = "ignore_muted"
        const val IGNORE_BOTS = "ignore_bots"
        const val IGNORED_GUILDS = "ignored_guilds"
        const val IGNORED_CHANNELS = "ignored_channels"
        const val DELETED_COLOR = 0xFFF04747.toInt()
    }
}

class MessageLoggerDiscoveryService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
