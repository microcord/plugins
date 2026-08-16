package dev.mantikafasi.manticord.reviewdb

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.discord.utilities.images.MGImages
import dev.mantikafasi.manticord.reviewdb.databinding.ReviewdbReviewRowBinding
import java.text.DateFormat
import java.util.Date

class ReviewDbAdapter(
    private val inflater: LayoutInflater,
    private val onVote: (ReviewDbReview, Boolean, Boolean?) -> Unit,
    private val onReport: (ReviewDbReview) -> Unit,
) : RecyclerView.Adapter<ReviewDbAdapter.Holder>() {
    var reviews: List<ReviewDbReview> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }
    var votes: Map<Int, Boolean> = emptyMap()
        set(value) {
            field = value
            notifyDataSetChanged()
        }
    var palette = ReviewDbPalette()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ReviewdbReviewRowBinding.inflate(inflater, parent, false),
    )

    override fun getItemCount() = reviews.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(reviews[position], votes[reviews[position].id])

    inner class Holder(private val binding: ReviewdbReviewRowBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(review: ReviewDbReview, vote: Boolean?) {
            binding.reviewdbAuthor.text = buildString {
                append(review.sender.username)
                review.timestamp.takeIf { it > 0L }?.let {
                    append("  ")
                    append(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(if (it > 100_000_000_000L) it else it * 1_000L)))
                }
            }
            binding.reviewdbAuthor.setTextColor(palette.muted)
            binding.reviewdbComment.setTextColor(palette.primary)
            binding.reviewdbScore.setTextColor(palette.primary)
            binding.root.background = GradientDrawable().apply {
                cornerRadius = binding.root.resources.displayMetrics.density * 8f
                setColor(palette.surface)
            }
            binding.reviewdbComment.text = review.comment
            MGImages.setRoundingParams(binding.reviewdbAvatar, cornerRadius = 0f, circle = true)
            MGImages.setImage(binding.reviewdbAvatar, review.sender.profilePhoto)
            binding.reviewdbScore.text = review.score.toString()
            binding.reviewdbUpvote.isEnabled = review.canVote
            binding.reviewdbDownvote.isEnabled = review.canVote
            binding.reviewdbUpvote.alpha = if (review.canVote) 1f else 0.35f
            binding.reviewdbDownvote.alpha = if (review.canVote) 1f else 0.35f
            binding.reviewdbUpvote.setColorFilter(if (vote == true) Color.rgb(59, 165, 92) else palette.muted)
            binding.reviewdbDownvote.setColorFilter(if (vote == false) Color.rgb(237, 66, 69) else palette.muted)
            binding.reviewdbUpvote.setOnClickListener { onVote(review, true, vote) }
            binding.reviewdbDownvote.setOnClickListener { onVote(review, false, vote) }
            binding.root.setOnLongClickListener {
                if (review.canVote) onReport(review)
                review.canVote
            }
        }
    }
}

data class ReviewDbPalette(
    val surface: Int = Color.rgb(32, 34, 37),
    val border: Int = Color.DKGRAY,
    val primary: Int = Color.WHITE,
    val muted: Int = Color.LTGRAY,
    val interactive: Int = Color.rgb(88, 101, 242),
)
