package com.terenac.opencourt

import android.animation.TimeInterpolator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class AdAdapter(
    private val onAdClick: (Advertisement) -> Unit
) : RecyclerView.Adapter<AdAdapter.AdViewHolder>() {

    private val ads = mutableListOf<Advertisement>()
    private val animatedPositions = mutableSetOf<Int>()

    // Ažurira listu oglasa koristeći DiffUtil za efikasne promene i resetuje animacije za nove stavke
    fun submitList(newAds: List<Advertisement>) {
        val diffCallback = AdDiffCallback(ads, newAds)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        ads.clear()
        ads.addAll(newAds)
        diffResult.dispatchUpdatesTo(this)
        if (newAds.size > ads.size) animatedPositions.clear()
    }

    // Kreira novi ViewHolder inflating-om ad_item layout-a
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AdViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.ad_item, parent, false)
        return AdViewHolder(view, onAdClick)
    }

    // Vezuje podatke oglasa za ViewHolder i dodaje slide-in animaciju ako stavka nije već animirana
    override fun onBindViewHolder(holder: AdViewHolder, position: Int) {
        val ad = ads[position]
        holder.bind(ad)

        if (!animatedPositions.contains(position)) {
            val animation = AnimationUtils.loadAnimation(
                holder.itemView.context,
                R.anim.slide_in_from_bottom
            )
            holder.itemView.startAnimation(animation)
            animatedPositions.add(position)
        }
    }

    // Zaustavlja shimmer animaciju kada se ViewHolder reciklira
    override fun onViewRecycled(holder: AdViewHolder) {
        super.onViewRecycled(holder)
        holder.stopShimmerIfAny()
    }

    // Vraća ukupan broj oglasa u listi
    override fun getItemCount(): Int = ads.size

    inner class AdViewHolder(
        itemView: View,
        private val onClick: (Advertisement) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val cardView: MaterialCardView = itemView.findViewById(R.id.cardAd)
        private val tvCategory: TextView = itemView.findViewById(R.id.tvAdCategory)
        private val tvName: TextView = itemView.findViewById(R.id.adName)
        private val tvDescription: TextView = itemView.findViewById(R.id.adDescription)
        private val tvAuthor: TextView = itemView.findViewById(R.id.adAuthor)
        private val tvPrice: TextView = itemView.findViewById(R.id.tvAdPrice)

        private val textPanel: ViewGroup = itemView.findViewById(R.id.textPanel)

        // Kreira zlatni gradient pozadinu za admin pinned oglase
        private fun makeGoldFill(): GradientDrawable {
            return GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor("#29FFD54F"),
                    Color.parseColor("#1AFBC02D")
                )
            ).apply {
                cornerRadius = dp(26).toFloat()
            }
        }


        private val decel: TimeInterpolator = DecelerateInterpolator()

        // Konvertuje dp vrednost u piksele na osnovu gustine ekrana
        private fun dp(v: Int): Int =
            (itemView.resources.displayMetrics.density * v).toInt()

        // Vraća koreni ViewGroup iz MaterialCardView-a
        private fun frameRoot(): ViewGroup = cardView.getChildAt(0) as ViewGroup

        // Kreira ili vraća shimmer overlay View za admin oglase
        private fun ensureShimmerOverlay(): View {
            val root = frameRoot()
            val tag = "admin_shimmer"
            root.findViewWithTag<View>(tag)?.let { return it }

            val overlay = View(itemView.context).apply {
                this.tag = tag
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(0x00FFFFFF, 0x55FFFFFF, 0x00FFFFFF)
                )
                alpha = 0f
            }
            root.addView(
                overlay,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            return overlay
        }

        // Pokreće beskonačnu shimmer animaciju koja se kreće sleva nadesno
        private fun startShimmer() {
            val overlay = ensureShimmerOverlay()
            overlay.clearAnimation()
            overlay.alpha = 0f
            overlay.post {
                overlay.scaleX = 0.3f
                overlay.translationX = -overlay.width.toFloat()
                overlay.alpha = 1f

                overlay.animate()
                    .translationX(overlay.width.toFloat())
                    .setDuration(1800L)
                    .setInterpolator(decel)
                    .withEndAction {
                        overlay.translationX = -overlay.width.toFloat()
                        overlay.postDelayed({ startShimmer() }, 700L)
                    }
                    .start()
            }
        }

        // Zaustavlja i sakriva shimmer animaciju ako postoji
        fun stopShimmerIfAny() {
            val v = frameRoot().findViewWithTag<View>("admin_shimmer")
            v?.animate()?.cancel()
            v?.alpha = 0f
        }

        // Primenjuje zlatni stil sa shimmer efektom za admin pinned oglase
        private fun applyAdminGoldStyle(adminClubName: String) {
            cardView.setCardBackgroundColor(android.graphics.Color.parseColor("#1AFFFFFF"))
            cardView.strokeColor = android.graphics.Color.parseColor("#FFD54F")
            cardView.strokeWidth = dp(2)
            tvAuthor.text = "Administrator — $adminClubName"

            textPanel.background = makeGoldFill()
            startShimmer()
        }

        // Primenjuje standardni stil za obične oglase i zaustavlja shimmer efekat
        private fun applyRegularStyle(author: String) {
            cardView.setCardBackgroundColor(android.graphics.Color.parseColor("#1AFFFFFF"))
            cardView.strokeColor = android.graphics.Color.parseColor("#33FFFFFF")
            cardView.strokeWidth = dp(1)
            tvAuthor.text = author
            textPanel.background = null
            stopShimmerIfAny()
        }

        // Popunjava ViewHolder sa podacima oglasa i primenjuje odgovarajući stil (admin ili regularan)
        fun bind(ad: Advertisement) {
            tvCategory.text = ad.category
            tvName.text = ad.title
            tvDescription.text = ad.description

            if (ad.price.isNotBlank()) {
                tvPrice.text = ad.price
                tvPrice.isVisible = true
            } else {
                tvPrice.isVisible = false
            }

            val now = System.currentTimeMillis()
            val isAdminPinned = ad.pinnedByAdmin && ad.pinnedUntil > now
            if (isAdminPinned) {
                applyAdminGoldStyle(ad.adminClubName.ifBlank { "Nepoznat klub" })
            } else {
                applyRegularStyle(ad.userName)
            }

            cardView.setOnClickListener { onClick(ad) }
        }
    }
}

class AdDiffCallback(
    private val oldList: List<Advertisement>,
    private val newList: List<Advertisement>
) : DiffUtil.Callback() {
    // Vraća veličinu stare liste oglasa
    override fun getOldListSize(): Int = oldList.size
    // Vraća veličinu nove liste oglasa
    override fun getNewListSize(): Int = newList.size
    // Proverava da li su stavke iste poređenjem njihovih ID-ova
    override fun areItemsTheSame(o: Int, n: Int): Boolean =
        oldList[o].id == newList[n].id
    // Proverava da li su sadržaji stavki identični
    override fun areContentsTheSame(o: Int, n: Int): Boolean =
        oldList[o] == newList[n]
}
