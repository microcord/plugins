package dev.mantikafasi.manticord.reviewdb

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class ReviewDbSender(
    val username: String,
    val profilePhoto: String?,
    val discordId: Long?,
)

data class ReviewDbReview(
    val id: Int,
    val comment: String,
    val score: Int,
    val timestamp: Long,
    val type: Int,
    val sender: ReviewDbSender,
) {
    val canVote get() = id != 0 && type == 0
}

data class ReviewDbReviewsPage(val reviews: List<ReviewDbReview>, val count: Int, val hasNextPage: Boolean, val rating: Int)
data class ReviewDbResult(val success: Boolean, val message: String, val updated: Boolean = false)
data class ReviewDbUser(val discordId: Long, val badgeNames: Set<String>)

class ReviewDbClient(private val token: () -> String, private val disableAds: () -> Boolean, private val disableWarnings: () -> Boolean) {
    fun execute(work: () -> Unit) = executor.execute(work)

    fun reviews(targetId: Long, offset: Int): ReviewDbReviewsPage? {
        var flags = 0
        if (disableAds()) flags = flags or FLAG_DISABLE_ADS
        if (disableWarnings()) flags = flags or FLAG_DISABLE_WARNINGS
        val response = request("/api/reviewdb/users/$targetId/reviews?flags=$flags&offset=$offset") ?: return null
        if (!response.optBoolean("success")) return null
        val reviews = response.optJSONArray("reviews").toReviews()
        return ReviewDbReviewsPage(
            reviews,
            response.optInt("reviewCount"),
            response.optBoolean("hasNextPage"),
            rating(targetId),
        )
    }

    fun rating(targetId: Long): Int = request("/api/reviewdb/users/$targetId/rating")?.optInt("rating") ?: 0

    fun votes(targetId: Long): Map<Int, Boolean> = authenticated("/api/reviewdb/users/$targetId/reviews/votes")
        ?.optJSONArray("votes")
        ?.let { votes -> buildMap {
            for (index in 0 until votes.length()) {
                val vote = votes.optJSONObject(index) ?: continue
                put(vote.optInt("reviewID", vote.optInt("reviewId")), vote.optBoolean("isUpvote"))
            }
        } }
        .orEmpty()

    fun addReview(targetId: Long, comment: String): ReviewDbResult = authenticatedResult(
        "/api/reviewdb/users/$targetId/reviews",
        "PUT",
        JSONObject().put("comment", comment).put("token", token()),
    )

    fun vote(reviewId: Int, upvote: Boolean): ReviewDbResult = authenticatedResult(
        "/api/reviewdb/reviews/$reviewId/vote",
        "POST",
        JSONObject().put("isUpvote", upvote),
    )

    fun removeVote(reviewId: Int): ReviewDbResult = authenticatedResult(
        "/api/reviewdb/reviews/$reviewId/vote",
        "DELETE",
        JSONObject(),
    )

    fun report(reviewId: Int): ReviewDbResult = authenticatedResult(
        "/api/reviewdb/reports",
        "POST",
        JSONObject().put("token", token()).put("reviewid", reviewId),
    )

    fun deleteReview(reviewId: Int): ReviewDbResult = authenticatedResult(
        "/api/reviewdb/users/0/reviews",
        "DELETE",
        JSONObject().put("token", token()).put("reviewid", reviewId),
    )

    fun setOptOut(optedOut: Boolean): ReviewDbResult = authenticatedResult(
        "/api/reviewdb/settings",
        "PATCH",
        JSONObject().put("opt", optedOut),
    )

    fun block(discordId: Long, blocked: Boolean): ReviewDbResult = authenticatedResult(
        "/api/reviewdb/blocks",
        "PATCH",
        JSONObject().put("action", if (blocked) "block" else "unblock").put("discordId", discordId.toString()),
    )

    fun blockedUsers(): List<ReviewDbSender> = authenticated("/api/reviewdb/blocks")
        ?.optJSONArray("users")
        ?.let { users -> buildList {
            for (index in 0 until users.length()) {
                val user = users.optJSONObject(index) ?: continue
                add(ReviewDbSender(user.optString("username", "unknown"), user.optString("profilePhoto").takeIf(String::isNotBlank), user.optLong("discordID").takeIf { it != 0L }))
            }
        } }
        .orEmpty()

    fun canUseEmoji(discordId: Long): Boolean {
        if (token().isBlank()) return false
        if (admins().contains(discordId)) return true
        val user = currentUser() ?: return false
        return user.discordId == discordId && user.badgeNames.any { it.equals("Donor", ignoreCase = true) }
    }

    fun authorizeUrl() = "https://discord.com/oauth2/authorize?client_id=$CLIENT_ID&redirect_uri=" +
        URLEncoder.encode("$BASE_URL/api/reviewdb/auth", StandardCharsets.UTF_8.name()) +
        "&response_type=code&scope=identify"

    fun tokenFromRedirect(url: String): String? = requestAbsolute(
        Uri.parse(url).buildUpon()
            .appendQueryParameter("returnType", "json")
            .appendQueryParameter("clientMod", "fairecord")
            .build()
            .toString(),
    )?.takeIf { it.optBoolean("success") }?.optString("token")

    private fun authenticated(path: String): JSONObject? = request(path, auth = token().takeIf(String::isNotBlank))

    private fun authenticatedResult(path: String, method: String, body: JSONObject): ReviewDbResult {
        val response = request(path, method, body, token().takeIf(String::isNotBlank))
            ?: return ReviewDbResult(false, "ReviewDB is unavailable")
        return ReviewDbResult(response.optBoolean("success"), response.optString("message", "Unknown error"), response.optBoolean("updated"))
    }

    private fun currentUser(): ReviewDbUser? {
        val response = request("/api/reviewdb/users", "POST", JSONObject().put("token", token())) ?: return null
        val discordId = response.optLong("discordID").takeIf { it != 0L } ?: return null
        val badgeNames = buildSet {
            val badges = response.optJSONArray("badges") ?: return@buildSet
            for (index in 0 until badges.length()) {
                badges.optJSONObject(index)?.optString("name")?.takeIf(String::isNotBlank)?.let(::add)
            }
        }
        return ReviewDbUser(discordId, badgeNames)
    }

    private fun admins(): Set<Long> = requestArray("/admins")?.let { admins ->
        buildSet {
            for (index in 0 until admins.length()) admins.optString(index).toLongOrNull()?.let(::add)
        }
    }.orEmpty()

    private fun request(path: String, method: String = "GET", body: JSONObject? = null, auth: String? = null): JSONObject? =
        requestAbsolute("$BASE_URL$path", method, body, auth)

    private fun requestAbsolute(url: String, method: String = "GET", body: JSONObject? = null, auth: String? = null): JSONObject? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            auth?.let { connection.setRequestProperty("Authorization", it) }
            body?.let {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer -> writer.write(it.toString()) }
            }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader(StandardCharsets.UTF_8)?.use { JSONObject(it.readText()) }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun requestArray(path: String): JSONArray? = runCatching {
        val connection = URL("$BASE_URL$path").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader(StandardCharsets.UTF_8)?.use { JSONArray(it.readText()) }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun JSONArray?.toReviews(): List<ReviewDbReview> = buildList {
        val array = this@toReviews ?: return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val sender = item.optJSONObject("sender") ?: JSONObject()
            add(
                ReviewDbReview(
                    item.optInt("id"), item.optString("comment"), item.optInt("score"), item.optLong("timestamp"), item.optInt("type"),
                    ReviewDbSender(sender.optString("username", "unknown"), sender.optString("profilePhoto").takeIf(String::isNotBlank), sender.optLong("discordID").takeIf { it != 0L }),
                ),
            )
        }
    }

    private companion object {
        const val BASE_URL = "https://manti.vendicated.dev"
        const val CLIENT_ID = "915703782174752809"
        const val FLAG_DISABLE_ADS = 1
        const val FLAG_DISABLE_WARNINGS = 2
        const val TIMEOUT_MS = 15_000
        const val USER_AGENT = "Fairecord/1.0 (Manticord ReviewDB)"
        val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "ReviewDB").apply { isDaemon = true } }
    }
}
