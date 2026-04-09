package com.terenac.opencourt

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class ReservationsAdapter(
    private val onItemClick: (UserReservation) -> Unit
) : RecyclerView.Adapter<ReservationsAdapter.ReservationViewHolder>() {

    private val reservations = mutableListOf<UserReservation>()

    // Ažurira listu rezervacija i osvežava celokupan RecyclerView
    fun submitList(newList: List<UserReservation>) {
        reservations.clear()
        reservations.addAll(newList)
        notifyDataSetChanged()
    }

    // Kreira novi ViewHolder inflating-om item_reservation_compact layout-a
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReservationViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_reservation_compact, parent, false)
        return ReservationViewHolder(view)
    }

    // Vezuje podatke rezervacije za ViewHolder na određenoj poziciji
    override fun onBindViewHolder(holder: ReservationViewHolder, position: Int) {
        holder.bind(reservations[position])
    }

    // Vraća ukupan broj rezervacija u listi
    override fun getItemCount(): Int = reservations.size

    inner class ReservationViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivCourtType: ImageView = itemView.findViewById(R.id.ivCourtType)
        private val tvCourtName: TextView = itemView.findViewById(R.id.tvCourtName)
        private val tvDateTime: TextView = itemView.findViewById(R.id.tvDateTime)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)

        // status (Predstoji/Aktivna/Završena/Otkazana) sa odgovarajućom bojom i postavlja click listener
        fun bind(reservation: UserReservation) {
            tvCourtName.text = "${reservation.clubName} • ${reservation.courtName}"

            reservation.startTime?.let { start ->
                reservation.endTime?.let { end ->
                    val sdf = SimpleDateFormat("dd. MMM yyyy, HH:mm", Locale("sr"))
                    val sdfTime = SimpleDateFormat("HH:mm", Locale("sr"))
                    tvDateTime.text = "${sdf.format(start.toDate())} - ${sdfTime.format(end.toDate())}"
                }
            }

            ivCourtType.setImageResource(R.drawable.court_hard)

            val now = Calendar.getInstance().time
            val isUpcoming = reservation.startTime?.toDate()?.after(now) == true

            when {
                reservation.status == "cancelled" -> {
                    tvStatus.text = "Otkazana"
                    tvStatus.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.holo_red_dark))
                }
                reservation.status == "completed" -> {
                    tvStatus.text = "Završena"
                    tvStatus.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.darker_gray))
                }
                isUpcoming -> {
                    tvStatus.text = "Predstoji"
                    tvStatus.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.holo_green_dark))
                }
                else -> {
                    tvStatus.text = "Aktivna"
                    tvStatus.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.holo_blue_dark))
                }
            }

            itemView.setOnClickListener {
                onItemClick(reservation)
            }
        }
    }
}
