package com.tvviewer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load

class TvGuideActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_guide)

        val channels = TvGuideChannels.channels
        val currentUrl = TvGuideChannels.currentUrl

        findViewById<Toolbar>(R.id.toolbar)?.apply {
            setSupportActionBar(this)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            setNavigationOnClickListener { finish() }
        }

        val recycler = findViewById<RecyclerView>(R.id.guideRecycler)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = GuideAdapter(channels, currentUrl) { channel ->
            setResult(RESULT_OK, Intent().apply { putExtra(EXTRA_CHANNEL_URL, channel.url) })
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        const val EXTRA_CHANNEL_URL = "channel_url"
    }
}

private class GuideAdapter(
    private val channels: List<Channel>,
    private val currentUrl: String?,
    private val onChannelClick: (Channel) -> Unit
) : RecyclerView.Adapter<GuideAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val logo: ImageView = view.findViewById(R.id.guideLogo)
        val name: TextView = view.findViewById(R.id.guideName)
        val now: TextView = view.findViewById(R.id.guideNow)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_guide, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val ch = channels[position]
        holder.name.text = ch.name
        holder.now.text = holder.itemView.context.getString(R.string.tv_guide_now)
        holder.logo.load(ch.logoUrl) {
            crossfade(true)
            error(android.R.drawable.ic_menu_gallery)
            placeholder(android.R.drawable.ic_menu_gallery)
        }
        holder.itemView.setBackgroundColor(
            if (ch.url == currentUrl) 0x33FFFFFF.toInt() else 0
        )
        holder.itemView.setOnClickListener { onChannelClick(ch) }
    }

    override fun getItemCount() = channels.size
}
