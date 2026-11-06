package com.vidgrab.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class VideoAdapter(private val items: List<VideoItem>, private val onClick: (VideoItem) -> Unit) : RecyclerView.Adapter<VideoAdapter.VH>() {
    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.title)
        val duration: TextView = view.findViewById(R.id.duration)
        val thumb: ImageView = view.findViewById(R.id.thumbnail)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
        return VH(view)
    }
    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.duration.text = item.duration
        Glide.with(holder.itemView.context).load(item.thumbnail).into(holder.thumb)
        holder.itemView.setOnClickListener { onClick(item) }
    }
    override fun getItemCount() = items.size
}

data class VideoItem(val title: String, val url: String, val thumbnail: String, val duration: String)
