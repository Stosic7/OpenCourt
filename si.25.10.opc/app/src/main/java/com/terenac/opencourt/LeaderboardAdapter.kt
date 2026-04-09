package com.terenac.opencourt

import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class LeaderboardAdapter : RecyclerView.Adapter<LeaderboardAdapter.LeaderboardViewHolder>() {

    private val entries = mutableListOf<LeaderboardEntry>()

    // Ažurira listu leaderboard unosa i osvežava celokupan RecyclerView
    fun submitList(newList: List<LeaderboardEntry>) {
        entries.clear()
        entries.addAll(newList)
        notifyDataSetChanged()
    }

    // Kreira novi ViewHolder inflating-om item_leaderboard_complete layout-a
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LeaderboardViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_leaderboard_complete, parent, false)
        return LeaderboardViewHolder(view)
    }

    // Vraća ukupan broj unosa na leaderboard-u
    override fun getItemCount(): Int = entries.size

    // Vezuje podatke leaderboard unosa za ViewHolder na određenoj poziciji
    override fun onBindViewHolder(holder: LeaderboardViewHolder, position: Int) {
        holder.bind(entries[position])
    }

    class LeaderboardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvRank: TextView = itemView.findViewById(R.id.tvRank)
        private val tvPlayerName: TextView = itemView.findViewById(R.id.tvPlayerName)
        private val tvScore: TextView = itemView.findViewById(R.id.tvScore)

        // Prikazuje rang, ime, poene, dodaje zlatnu shadow za admin-e i highlightuje trenutnog korisnika plavom pozadinom
        fun bind(entry: LeaderboardEntry) {
            tvRank.text = "${entry.rank}."
            tvPlayerName.setSingleLine(false)
            tvPlayerName.maxLines = 2
            tvPlayerName.ellipsize = null
            tvPlayerName.text = buildName(entry)

            tvScore.text = "${entry.points} 🏆"

            if (entry.isAdmin) {
                tvPlayerName.setShadowLayer(8f, 0f, 0f, Color.parseColor("#80FFD700"))
            } else {
                tvPlayerName.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            }

            if (entry.isCurrentUser) {
                itemView.setBackgroundColor(
                    ContextCompat.getColor(itemView.context, android.R.color.holo_blue_light)
                )
            } else {
                itemView.setBackgroundColor(
                    ContextCompat.getColor(itemView.context, android.R.color.transparent)
                )
            }
        }

        // Formatira ime korisnika sa "Administrator" labelom u zlatnoj boji i manjim fontom ako je admin
        private fun buildName(entry: LeaderboardEntry): CharSequence {
            if (!entry.isAdmin) return entry.userName
            val label = "Administrator"
            val gold = Color.parseColor("#FFD700")
            val sb = SpannableStringBuilder()
                .append(entry.userName)
                .append("\n")
                .append(label)
            val start = sb.length - label.length
            val end = sb.length
            sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.85f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(gold), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return sb
        }
    }
}
