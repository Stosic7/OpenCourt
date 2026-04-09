package com.terenac.opencourt

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ShopAdapter(
    private val items: MutableList<ShopItem>,
    private val onItemClick: ((item: ShopItem, position: Int) -> Unit)? = null
) : RecyclerView.Adapter<ShopAdapter.VH>() {

    class VH(parent: ViewGroup) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_shop_card, parent, false)
    ) {
        val img: ImageView = itemView.findViewById(R.id.img)
        val title: TextView = itemView.findViewById(R.id.title)
        val subtitle: TextView = itemView.findViewById(R.id.subtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(parent)

    override fun onBindViewHolder(h: VH, position: Int) {
        val it = items[position]
        h.title.text = it.title
        h.subtitle.text = it.subtitle
        h.img.setImageResource(it.imageRes)

        h.itemView.setOnClickListener {
            val pos = h.adapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onItemClick?.invoke(items[pos], pos)
            }
        }
    }

    override fun getItemCount() = items.size

    fun submit(newList: List<ShopItem>) {
        items.clear()
        items.addAll(newList)
        notifyDataSetChanged()
    }
}
