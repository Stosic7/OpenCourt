package com.terenac.opencourt

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.collection.LruCache
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import kotlin.math.round

class ClubsAdapter(
    private var items: List<Club>,
    private val onClick: (Club) -> Unit
) : RecyclerView.Adapter<ClubsAdapter.ClubVH>() {

    companion object {
        private val bitmapCache = object : LruCache<String, Bitmap>(/* max entries */ 64) {}
        // Dekodira data URL (Base64) u Bitmap objekat, vraća null ako dekodiranje ne uspe
        private fun decodeDataUrlToBitmap(dataUrl: String): Bitmap? {
            val comma = dataUrl.indexOf(',')
            if (comma <= 0) return null
            val base64 = dataUrl.substring(comma + 1)
            return try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (_: Throwable) {
                null
            }
        }
    }

    inner class ClubVH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.imgClub)
        val name: TextView = v.findViewById(R.id.tvClubName)
        val distance: TextView = v.findViewById(R.id.tvDistance)
    }

    // Kreira novi ViewHolder inflating-om item_club layout-a
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ClubVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_club, parent, false)
        return ClubVH(v)
    }

    // Prikazuje naziv kluba, formatira udaljenost (m/km), učitava thumbnail iz cache-a ili data URL-a sa fallback-om na default sliku
    override fun onBindViewHolder(holder: ClubVH, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.distance.text = when {
            item.distanceMeters == Int.MAX_VALUE -> "Udaljenost – …"
            item.distanceMeters < 950 -> String.format(Locale.getDefault(), "Udaljenost – %d m", item.distanceMeters)
            item.distanceMeters < 20_000 -> String.format(Locale.getDefault(), "Udaljenost – %.1f km", item.distanceMeters / 1000.0)
            else -> String.format(Locale.getDefault(), "Udaljenost – %d km", round(item.distanceMeters / 1000.0).toInt())
        }

        val thumb = item.thumbDataUrl
        if (!thumb.isNullOrBlank() && thumb.startsWith("data:image")) {
            val cached = bitmapCache.get(thumb)
            if (cached != null) {
                holder.img.setImageBitmap(cached)
            } else {
                val bmp = decodeDataUrlToBitmap(thumb)
                if (bmp != null) {
                    bitmapCache.put(thumb, bmp)
                    holder.img.setImageBitmap(bmp)
                } else {
                    holder.img.setImageResource(R.drawable.tenis_court)
                }
            }
        } else {
            holder.img.setImageResource(R.drawable.tenis_court)
        }

        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.isClickable = true
        holder.itemView.isFocusable = true
    }

    // Vraća ukupan broj klubova u listi
    override fun getItemCount(): Int = items.size

    // Ažurira listu klubova i notifikuje adapter o promenama
    fun submit(newItems: List<Club>) {
        items = newItems
        notifyDataSetChanged()
    }
}
