package com.terenac.opencourt

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

data class Comment(
    val id: String = "",
    val userName: String = "",
    val text: String = "",
    val timestamp: Long = 0L
)

class CommentsAdapter : RecyclerView.Adapter<CommentsAdapter.CommentViewHolder>() {

    private val comments = mutableListOf<Comment>()

    // Ažurira listu komentara koristeći DiffUtil za efikasne promene u RecyclerView-u
    fun submitList(newComments: List<Comment>) {
        val diffCallback = CommentDiffCallback(comments, newComments)
        val diffResult = DiffUtil.calculateDiff(diffCallback)

        comments.clear()
        comments.addAll(newComments)
        diffResult.dispatchUpdatesTo(this)
    }

    // Kreira novi ViewHolder inflating-om item_comment layout-a
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_comment, parent, false)
        return CommentViewHolder(view)
    }

    // Vezuje podatke komentara za ViewHolder na određenoj poziciji
    override fun onBindViewHolder(holder: CommentViewHolder, position: Int) {
        holder.bind(comments[position])
    }

    // Vraća ukupan broj komentara u listi
    override fun getItemCount(): Int = comments.size

    class CommentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvAuthor: TextView = itemView.findViewById(R.id.tvCommentAuthor)
        private val tvText: TextView = itemView.findViewById(R.id.tvCommentText)
        private val tvTime: TextView = itemView.findViewById(R.id.tvCommentTime)

        // Popunjava ViewHolder sa podacima komentara (autor, tekst, formatiran timestamp)
        fun bind(comment: Comment) {
            tvAuthor.text = comment.userName
            tvText.text = comment.text
            tvTime.text = formatTimestamp(comment.timestamp)
        }

        // Formatira timestamp u čitljiv format: "Upravo sad", "Pre X min/h", "Juče" ili pun datum
        private fun formatTimestamp(timestamp: Long): String {
            val now = System.currentTimeMillis()
            val diff = now - timestamp

            return when {
                diff < 60_000 -> "Upravo sad"
                diff < 3_600_000 -> "Pre ${diff / 60_000} min"
                diff < 86_400_000 -> "Pre ${diff / 3_600_000} h"
                diff < 172_800_000 -> "Juče"
                else -> {
                    val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
                    dateFormat.format(Date(timestamp))
                }
            }
        }
    }
}

class CommentDiffCallback(
    private val oldList: List<Comment>,
    private val newList: List<Comment>
) : DiffUtil.Callback() {
    // Vraća veličinu stare liste komentara
    override fun getOldListSize(): Int = oldList.size
    // Vraća veličinu nove liste komentara
    override fun getNewListSize(): Int = newList.size

    // Proverava da li su komentari isti poređenjem njihovih ID-ova
    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldList[oldItemPosition].id == newList[newItemPosition].id
    }

    // Proverava da li su sadržaji komentara identični
    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldList[oldItemPosition] == newList[newItemPosition]
    }
}
