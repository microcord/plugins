package dev.mantikafasi.manticord.reviewdb

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.view.View
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.stores.StoreStream
import com.discord.utilities.lifecycle.ActivityProvider
import dev.mantikafasi.manticord.plugins.api.PluginScope
import dev.mantikafasi.manticord.reviewdb.databinding.ReviewdbPageBinding

class ReviewDbPage(
    private val scope: PluginScope,
    private val client: ReviewDbClient,
    val targetId: Long,
    private val title: String,
) {
    private var offset = 0
    private var hasNext = false
    private var initialized = false
    private var loaded = false
    @Volatile private var emojiAccess: Boolean? = null
    private var currentBinding: ReviewdbPageBinding? = null
    private val adapter = ReviewDbAdapter(requireNotNull(scope.resources.inflater), ::vote, ::reportOrDelete)
    private var palette = ReviewDbPalette()

    fun create(context: Context): View {
        val binding = ReviewdbPageBinding.inflate(requireNotNull(scope.resources.inflater))
        bind(binding)
        return binding.root
    }

    fun bind(binding: ReviewdbPageBinding, profilePalette: ReviewDbPalette? = null) {
        currentBinding = binding
        profilePalette?.let { palette = it }
        binding.root.setBackgroundColor(Color.TRANSPARENT)
        binding.reviewdbTitle.setTextColor(palette.primary)
        binding.reviewdbNote.setTextColor(palette.muted)
        binding.reviewdbEmpty.setTextColor(palette.muted)
        binding.reviewdbPrevious.setTextColor(palette.interactive)
        binding.reviewdbNext.setTextColor(palette.interactive)
        binding.reviewdbPage.setTextColor(palette.muted)
        binding.reviewdbInputWrap.background = GradientDrawable().apply {
            cornerRadius = binding.root.resources.displayMetrics.density * 8f
            setColor(palette.surface)
            setStroke(binding.root.resources.displayMetrics.density.toInt().coerceAtLeast(1), palette.border)
        }
        binding.reviewdbInput.backgroundTintList = ColorStateList.valueOf(palette.surface)
        binding.reviewdbInput.setTextColor(palette.primary)
        binding.reviewdbInput.setHintTextColor(palette.muted)
        binding.reviewdbInput.setOnSend { submit(binding) }
        binding.reviewdbEmoji.setImageDrawable(
            ActivityProvider.getActivity()?.let { ContextCompat.getDrawable(it, R.drawable.ic_emoji_24dp) },
        )
        binding.reviewdbEmoji.setColorFilter(palette.primary)
        binding.reviewdbEmoji.setOnClickListener { binding.reviewdbInput.showEmojiPicker() }
        binding.reviewdbSubmit.setColorFilter(palette.primary)
        adapter.palette = palette
        binding.reviewdbTitle.text = title
        if (!initialized) {
            initialized = true
            binding.reviewdbReviews.layoutManager = LinearLayoutManager(binding.root.context)
            binding.reviewdbReviews.adapter = adapter
            binding.reviewdbSubmit.setOnClickListener { submit(binding) }
            binding.reviewdbPrevious.setOnClickListener { if (offset > 0) load(binding, (offset - PAGE_SIZE).coerceAtLeast(0)) }
            binding.reviewdbNext.setOnClickListener { if (hasNext) load(binding, offset + PAGE_SIZE) }
        }
        updateEmojiAccess(binding)
        if (!loaded) {
            loaded = true
            load(binding, 0)
        }
    }

    private fun updateEmojiAccess(binding: ReviewdbPageBinding) {
        emojiAccess?.let { allowed ->
            binding.reviewdbEmoji.visibility = if (allowed) View.VISIBLE else View.GONE
            return
        }
        binding.reviewdbEmoji.visibility = View.GONE
        client.execute {
            val allowed = client.canUseEmoji(StoreStream.getUsers().getMe().id)
            emojiAccess = allowed
            binding.root.post {
                binding.reviewdbEmoji.visibility = if (allowed) View.VISIBLE else View.GONE
            }
        }
    }

    private fun load(binding: ReviewdbPageBinding, requestedOffset: Int) {
        client.execute {
            val page = client.reviews(targetId, requestedOffset)
            val votes = client.votes(targetId)
            binding.root.post {
                if (page == null) {
                    binding.reviewdbEmpty.visibility = View.VISIBLE
                    binding.reviewdbEmpty.text = "ReviewDB is unavailable"
                    return@post
                }
                offset = requestedOffset
                hasNext = page.hasNextPage
                adapter.reviews = page.reviews
                adapter.votes = votes
                binding.reviewdbTitle.text = "$title (${page.count}, rating ${page.rating})"
                binding.reviewdbEmpty.visibility = if (page.reviews.isEmpty()) View.VISIBLE else View.GONE
                binding.reviewdbPage.text = "Page ${offset / PAGE_SIZE + 1}"
                binding.reviewdbPrevious.alpha = if (offset > 0) 1f else 0.35f
                binding.reviewdbNext.alpha = if (hasNext) 1f else 0.35f
            }
        }
    }

    private fun submit(binding: ReviewdbPageBinding) {
        val review = binding.reviewdbInput.text?.toString()?.trim().orEmpty()
        if (scope.settings.getString(TOKEN_KEY).isBlank()) {
            ReviewDbPlugin.authorize(scope, client)
            return
        }
        if (review.isEmpty() || review.length > 500) {
            scope.dialogs.alert("ReviewDB", "Reviews must be between 1 and 500 characters.")
            return
        }
        client.execute {
            val result = client.addReview(targetId, review)
            binding.root.post {
                scope.dialogs.alert("ReviewDB", result.message.ifBlank { if (result.success) "Review saved" else "Review failed" })
                if (result.success) {
                    binding.reviewdbInput.setText("")
                    load(binding, 0)
                }
            }
        }
    }

    private fun vote(review: ReviewDbReview, upvote: Boolean, existingVote: Boolean?) {
        if (scope.settings.getString(TOKEN_KEY).isBlank()) {
            ReviewDbPlugin.authorize(scope, client)
            return
        }
        client.execute {
            val removing = existingVote == upvote
            val result = if (removing) client.removeVote(review.id) else client.vote(review.id, upvote)
            val binding = currentBinding ?: return@execute
            binding.root.post {
                Toast.makeText(
                    binding.root.context,
                    result.message.ifBlank { if (result.success) if (removing) "Vote removed" else "Vote saved" else "Vote failed" },
                    Toast.LENGTH_SHORT,
                ).show()
                if (result.success) load(binding, offset)
            }
        }
    }

    private fun reportOrDelete(review: ReviewDbReview) {
        if (scope.settings.getString(TOKEN_KEY).isBlank()) {
            ReviewDbPlugin.authorize(scope, client)
            return
        }
        val ownReview = review.sender.discordId == StoreStream.getUsers().getMe().id
        scope.dialogs.confirm(
            title = if (ownReview) "Delete review?" else "Report review?",
            message = if (ownReview) "This cannot be undone." else "Report this review to ReviewDB?",
            confirmLabel = if (ownReview) "Delete" else "Report",
        ) {
            performReviewAction(review, ownReview)
        }
    }

    private fun performReviewAction(review: ReviewDbReview, ownReview: Boolean) {
        client.execute {
            val result = if (ownReview) client.deleteReview(review.id) else client.report(review.id)
            scope.runOnMainThread {
                scope.dialogs.alert("ReviewDB", result.message.ifBlank { if (result.success) if (ownReview) "Review deleted" else "Review reported" else "Request failed" })
            }
        }
    }

    private companion object {
        const val PAGE_SIZE = 50
        const val TOKEN_KEY = "token"
    }
}
