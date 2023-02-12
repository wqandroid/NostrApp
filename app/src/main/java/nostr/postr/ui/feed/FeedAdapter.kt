package nostr.postr.ui.feed

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import fr.acinq.secp256k1.Hex
import nostr.postr.databinding.FragmentFeedItemBinding
import nostr.postr.db.FeedItem
import nostr.postr.db.UserProfile
import nostr.postr.toNpub
import nostr.postr.util.UIUtils.makeGone
import nostr.postr.util.UIUtils.makeVisibility
import java.util.regex.Pattern

data class Feed(val feedItem: FeedItem, val userProfile: UserProfile?) {
    var replyTos: List<String>? = null
    var mentions: List<String>? = null
}

class FeedAdapter(var listData: MutableList<Feed>) :
    RecyclerView.Adapter<FeedAdapter.FeedViewHolder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FeedViewHolder {

        val binding = FragmentFeedItemBinding
            .inflate(LayoutInflater.from(parent.context), parent, false)

        return FeedViewHolder(binding)
    }

    var clickListener: ItemChildClickListener? = null

    val p =
        Pattern.compile("https?:[^:<>\"]*\\/([^:<>\"]*)\\.((png!thumbnail)|(png)|(jpg)|(webp))")

    override fun onBindViewHolder(holder: FeedViewHolder, position: Int) {
        val item: Feed = listData[position]
        holder.binding.tvContent.text = item.feedItem.content
        holder.binding.tvTime.text = parseTime(item.feedItem.created_at)

        if (item.replyTos.isNullOrEmpty()) {
            holder.binding.tvReply.makeGone()
        } else {
            holder.binding.tvReply.makeVisibility()
            holder.binding.tvReply.text = "@reply${item.replyTos!![0]}"
        }

        if (item.userProfile == null) {
            holder.binding.ivLn6.isVisible = false
            holder.binding.tvName.text = ""
            holder.binding.tvDisplayName.text = Hex.decode(item.feedItem.pubkey).toNpub()
        } else {
            holder.binding.tvDisplayName.text = item.userProfile.display_name
            holder.binding.tvName.text = "@${item.userProfile.name}"
            holder.binding.ivLn6.isVisible = item.userProfile.lud16?.isNotEmpty() == true
        }


        Glide.with(holder.binding.ivAvatar).load(item.userProfile?.picture).into(
            holder.binding.ivAvatar
        )

        val m = p.matcher(item.feedItem.content)
        if (m.find()) {
//            Log.e("matches", "--->${m.group()}---${item.feedItem.content}")
            holder.binding.ivContentImg.visibility = View.VISIBLE
            Glide.with(holder.binding.ivAvatar).load(m.group()).into(
                holder.binding.ivContentImg
            )
        } else {
            holder.binding.ivContentImg.visibility = View.GONE
        }

        holder.binding.ivMore.setOnClickListener {
            clickListener?.onClick(item, it)
        }
        holder.binding.ivAvatar.setOnClickListener {
            clickListener?.onClick(item, it)
        }
    }


    private fun parseTime(time: Long): String {
        val now = System.currentTimeMillis() / 1000
        val du = now - time
        return if (du < 60) {
            "$du 分钟前"
        } else if (du < 60 * 60) {
            "${du / 60} 分钟前"
        } else if (du < 24 * 60 * 60) {
            "${du / 3600} 小时前"
        } else {
            "${du / (3600 * 24)} 天前"
        }


    }

    fun updateData(list: List<Feed>) {
        listData.clear()
        listData.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount() = listData.size

    inner class FeedViewHolder(val binding: FragmentFeedItemBinding) :
        RecyclerView.ViewHolder(binding.root)


    interface ItemChildClickListener {
        fun onClick(feed: Feed, itemView: View)
    }
}