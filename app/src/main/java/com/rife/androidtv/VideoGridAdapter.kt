package com.rife.androidtv

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView

class VideoGridAdapter(
    private val context: Context,
    private val items: List<MediaFileItem>
) : BaseAdapter() {

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): Any = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_video_card, parent, false)

        val item = items[position]
        val imgThumbnail = view.findViewById<ImageView>(R.id.imgThumbnail)
        val tvTitle = view.findViewById<TextView>(R.id.tvVideoTitle)
        val tvDetails = view.findViewById<TextView>(R.id.tvVideoDetails)

        tvTitle.text = item.title
        tvDetails.text = item.sizeText

        if (item.thumbnail != null) {
            imgThumbnail.setImageBitmap(item.thumbnail)
        } else {
            imgThumbnail.setImageResource(android.R.drawable.ic_menu_slideshow)
        }

        return view
    }
}
