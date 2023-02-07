package nostr.postr.feed

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import nostr.postr.R
import nostr.postr.Utils
import nostr.postr.databinding.FragmentFeedItemBinding
import nostr.postr.db.FeedItem
import nostr.postr.db.UserProfile
import java.util.regex.Matcher
import java.util.regex.Pattern

data class Feed(val feedItem: FeedItem, val userProfile: UserProfile?)

class FeedAdapter(var listData: MutableList<Feed>) :
    RecyclerView.Adapter<FeedAdapter.FeedViewHolder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FeedViewHolder {

        val binding = FragmentFeedItemBinding
            .inflate(LayoutInflater.from(parent.context), parent, false)

        return FeedViewHolder(binding)
    }


    val p =
        Pattern.compile("https?:[^:<>\"]*\\/([^:<>\"]*)\\.((png!thumbnail)|(png)|(jpg)|(webp))")

    override fun onBindViewHolder(holder: FeedViewHolder, position: Int) {
        val item: Feed = listData[position]
        holder.binding.tvContent.text = item.feedItem.content
        holder.binding.tvTime.text = parseTime(item.feedItem.created_at)
        holder.binding.tvName.text = item.userProfile?.name ?: item.feedItem.pubkey

        Glide.with(holder.binding.ivAvatar).load(item.userProfile?.picture).into(
            holder.binding.ivAvatar
        )


        val m = p.matcher(item.feedItem.content)
        if (m.find()){
            Log.e("matches","--->${m.group()}---${item.feedItem.content}")
            holder.binding.ivContentImg.visibility=View.VISIBLE
            Glide.with(holder.binding.ivAvatar).load(m.group()).into(
                holder.binding.ivContentImg
            )
        }else{
            holder.binding.ivContentImg.visibility=View.GONE
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

}