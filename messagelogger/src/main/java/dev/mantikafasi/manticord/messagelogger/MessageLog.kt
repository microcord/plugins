package dev.mantikafasi.manticord.messagelogger

/**
 * What this session has seen deleted or edited, by message ID. Kept in memory only, so it is empty
 * after a restart.
 *
 * Written on the store thread and read on the main thread, so every access holds the lock. Capped,
 * oldest first, so a long session in busy channels cannot grow it without bound.
 */
internal class MessageLog(private val capacity: Int = 2_000) {
    class Edit(val content: String, val editedAt: Long)

    class Record(val channelId: Long) {
        var deletedAt: Long? = null
        val edits = mutableListOf<Edit>()
    }

    private val records = object : LinkedHashMap<Long, Record>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Record>) = size > capacity
    }

    @Synchronized fun get(messageId: Long): Record? = records[messageId]

    @Synchronized fun isDeleted(messageId: Long) = records[messageId]?.deletedAt != null

    @Synchronized fun markDeleted(messageId: Long, channelId: Long, at: Long) {
        records.getOrPut(messageId) { Record(channelId) }.deletedAt = at
    }

    @Synchronized fun addEdit(messageId: Long, channelId: Long, previousContent: String, at: Long) {
        records.getOrPut(messageId) { Record(channelId) }.edits += Edit(previousContent, at)
    }

    @Synchronized fun remove(messageId: Long): Record? = records.remove(messageId)

    /** Empties the log and returns the deleted messages it was keeping, as message ID to channel ID. */
    @Synchronized fun clear(): Map<Long, Long> {
        val deleted = records.filterValues { it.deletedAt != null }.mapValues { it.value.channelId }
        records.clear()
        return deleted
    }
}
