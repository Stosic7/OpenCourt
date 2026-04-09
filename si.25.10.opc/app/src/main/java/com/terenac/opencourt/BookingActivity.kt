package com.terenac.opencourt

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.google.android.material.button.MaterialButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

class BookingActivity : AppCompatActivity() {

    private lateinit var tvClubCourt: TextView
    private lateinit var btnPickDate: Button
    private lateinit var spinnerDuration: Spinner
    private lateinit var npPlayers: NumberPicker
    private lateinit var rvTimeSlots: RecyclerView
    private lateinit var tvPrice: TextView
    private lateinit var tvSlotsHeader: TextView
    private lateinit var btnReserve: Button
    private lateinit var btnRentEquipment: Button
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val clubId: String by lazy { intent.getStringExtra(EXTRA_CLUB_ID) ?: "" }
    private val courtId: String by lazy { intent.getStringExtra(EXTRA_COURT_ID) ?: "" }

    private val OPEN_HOUR = 9
    private val CLOSE_HOUR = 21
    private val GRID_MINUTES = 15

    private var selectedDate: Calendar = todayAtMidnight()
    private var selectedDurationMin: Int = 60
    private var selectedSlot: Slot? = null
    private var todaysNow: Calendar = Calendar.getInstance()
    private var dayReservations: List<Window> = emptyList()
    private lateinit var slotsAdapter: SlotsAdapter
    private var slots: List<Slot> = emptyList()

    private var rentedRackets: Int = 0
    private var rentedBalls: Int = 0

    // Inicijalizuje UI komponente, setup-uje sve kontrole (datum, duration, players), učitava rezervacije i nazive kluba/terena
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_booking)

        if (clubId.isEmpty() || courtId.isEmpty()) {
            Toast.makeText(this, "Nedostaje clubId/courtId.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        initViews()
        setupBottomNavigation()
        setupPlayers()
        setupDurationSpinner()
        setupDatePicker()
        setupButtons()

        rvTimeSlots.layoutManager = LinearLayoutManager(this)
        slotsAdapter = SlotsAdapter { s, adapterPos ->
            if (!s.available) return@SlotsAdapter
            selectedSlot = s
            slotsAdapter.setSelected(adapterPos)
            btnReserve.isEnabled = true
            btnReserve.alpha = 1f
            setRentEnabled(true)
            updatePriceAndNote()
        }
        rvTimeSlots.adapter = slotsAdapter

        supportFragmentManager.setFragmentResultListener(EquipmentDialogFragment.RESULT_KEY, this) { _, bundle ->
            rentedRackets = bundle.getInt(EquipmentDialogFragment.KEY_RACKETS, 0)
            rentedBalls = bundle.getInt(EquipmentDialogFragment.KEY_BALLS, 0)
            updatePriceAndNote()
        }

        loadReservationsForDay { reloadSlots() }
        loadClubAndCourtNames()
    }

    // Pronalazi sve UI komponente, onemogućava dugmad za rezervaciju i rent dok slot nije izabran
    private fun initViews() {
        tvClubCourt = findViewById(R.id.tvClubCourt)
        btnPickDate = findViewById(R.id.btnPickDate)
        spinnerDuration = findViewById(R.id.spinnerDuration)
        npPlayers = findViewById(R.id.npPlayers)
        rvTimeSlots = findViewById(R.id.rvTimeSlots)
        tvPrice = findViewById(R.id.tvPrice)
        tvSlotsHeader = findViewById(R.id.tvSlotsHeader)
        btnReserve = findViewById(R.id.btnReserve)
        btnRentEquipment = findViewById(R.id.btnRentEquipment)
        btnReserve.isEnabled = false
        btnReserve.alpha = 0.5f
        setRentEnabled(false)
        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

    }

    // Postavlja bottom navigation sa navigacijom između Home, Advertisement i Profile ekrana
    private fun setupBottomNavigation() {
        val bottom = findViewById<BottomNavigationView>(R.id.bottom_navigation) ?: return
        bottom.selectedItemId = 0

        bottom.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_map -> {
                    val intent = Intent(this, HomeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    startActivity(intent); finish(); true
                }
                R.id.nav_sports -> {
                    val intent = Intent(this, AdvertisementActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    startActivity(intent); finish(); true
                }
                R.id.nav_profile -> {
                    val intent = Intent(this, ProfileActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    startActivity(intent); finish(); true
                }
                else -> false
            }
        }
    }

    // Konfigurira NumberPicker za broj igrača (1-4, default 2)
    private fun setupPlayers() {
        npPlayers.minValue = 1
        npPlayers.maxValue = 4
        npPlayers.wrapSelectorWheel = true
        npPlayers.value = 2
    }

    // Postavlja spinner sa trajanjima (60-180 min), pri promeni resetuje selekciju i reload-uje slotove
    private fun setupDurationSpinner() {
        val durations = listOf(60, 90, 120, 150, 180)
        val labels = durations.map { "$it min" }
        spinnerDuration.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        spinnerDuration.setSelection(0)
        spinnerDuration.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedDurationMin = durations[position]
                selectedSlot = null
                btnReserve.isEnabled = false
                btnReserve.alpha = 0.5f
                setRentEnabled(false)
                loadReservationsForDay { reloadSlots() }
                updatePriceAndNote()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // Prikazuje trenutni datum i otvara DatePickerDialog sa ograničenjem na današnji i buduće datume, pri promeni reload-uje rezervacije i slotove
    private fun setupDatePicker() {
        val sdf = SimpleDateFormat("EEE, dd. MMM yyyy.", Locale("sr"))
        btnPickDate.text = sdf.format(selectedDate.time)
        btnPickDate.setOnClickListener {
            val c = selectedDate
            val datePicker = DatePickerDialog(
                this,
                R.style.CustomDatePickerTheme,
                { _, y, m, d ->
                    selectedDate.set(y, m, d, 0, 0, 0)
                    selectedDate.set(Calendar.MILLISECOND, 0)
                    btnPickDate.text = sdf.format(selectedDate.time)
                    selectedSlot = null
                    btnReserve.isEnabled = false
                    btnReserve.alpha = 0.5f
                    setRentEnabled(false)
                    loadReservationsForDay { reloadSlots() }
                },
                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
            )
            datePicker.datePicker.minDate = System.currentTimeMillis() - 1000
            datePicker.show()
        }
    }

    // Postavlja click listenere za dugmad rent equipment i reserve
    private fun setupButtons() {
        btnRentEquipment.setOnClickListener {
            if (selectedSlot == null) {
                Toast.makeText(this, "Prvo izaberi termin.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            EquipmentDialogFragment.newInstance().show(supportFragmentManager, "EquipmentDialog")
        }
        btnReserve.setOnClickListener { saveReservation() }
    }

    // Dohvata nazive kluba i terena iz Firestore-a i prikazuje ih u formatu "Klub • Teren"
    private fun loadClubAndCourtNames() {
        db.collection("clubs").document(clubId).get()
            .addOnSuccessListener { clubDoc ->
                val clubName = clubDoc.getString("name") ?: clubId
                db.collection("clubs").document(clubId)
                    .collection("courts").document(courtId).get()
                    .addOnSuccessListener { courtDoc ->
                        val courtName = courtDoc.getString("name") ?: courtId
                        tvClubCourt.text = "$clubName • $courtName"
                    }
                    .addOnFailureListener { tvClubCourt.text = clubName }
            }
            .addOnFailureListener { /* ignore */ }
    }

    // Učitava sve aktivne rezervacije za izabrani dan iz Firestore-a i poziva callback nakon završetka
    private fun loadReservationsForDay(onDone: () -> Unit) {
        val start = selectedDate.clone() as Calendar
        val end = (selectedDate.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
        }

        db.collection("clubs").document(clubId)
            .collection("courts").document(courtId)
            .collection("reservations")
            .whereGreaterThanOrEqualTo("startTime", Timestamp(start.time))
            .whereLessThanOrEqualTo("startTime", Timestamp(end.time))
            .get()
            .addOnSuccessListener { snap ->
                dayReservations = snap.documents.mapNotNull { d ->
                    val st = d.getTimestamp("startTime")?.toDate()?.time ?: return@mapNotNull null
                    val et = d.getTimestamp("endTime")?.toDate()?.time ?: return@mapNotNull null
                    val status = d.getString("status") ?: "active"
                    if (status == "cancelled") null else Window(st, et)
                }
                onDone()
            }
            .addOnFailureListener {
                dayReservations = emptyList()
                onDone()
            }
    }

    // Generiše sve moguće slotove za izabrani dan (9-21h) sa grid-om od 15 min i proverava dostupnost
    private fun reloadSlots() {
        val day = selectedDate.clone() as Calendar
        val open = day.apply {
            set(Calendar.HOUR_OF_DAY, OPEN_HOUR); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val close = (selectedDate.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, CLOSE_HOUR); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val result = mutableListOf<Slot>()
        var t = open
        while (t + selectedDurationMin * 60_000L <= close) {
            val wnd = Window(t, t + selectedDurationMin * 60_000L)
            val avail = isAvailable(wnd)
            result.add(Slot(wnd.start, wnd.end, avail))
            t += GRID_MINUTES * 60_000L
        }
        slots = result
        slotsAdapter.submit(slots, resetSelection = true)
        tvSlotsHeader.text = "Slobodni termini (${formatDateLabel(selectedDate.time)} — ${selectedDurationMin} min)"
    }

    // Proverava da li je slot dostupan (nije prošao i ne preklapa se sa postojećim rezervacijama)
    private fun isAvailable(w: Window): Boolean {
        val nowDay = sameDay(selectedDate, todaysNow)
        if (nowDay && w.start < System.currentTimeMillis()) return false
        for (res in dayReservations) if (overlaps(w, res)) return false
        return true
    }

    // Proverava da li se dva vremenska prozora preklapaju
    private fun overlaps(a: Window, b: Window): Boolean {
        val s = max(a.start, b.start)
        val e = minOf(a.end, b.end)
        return e > s
    }

    // Poredi da li dva Calendar objekta predstavljaju isti dan (godina i dan u godini)
    private fun sameDay(a: Calendar, b: Calendar): Boolean {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }

    // Računa ukupnu cenu na osnovu trajanja (500-1000 RSD/h zavisno od sata), reketa (200 RSD) i loptica (50 RSD)
    private fun calculatePrice(durationMin: Int, rackets: Int, balls: Int): Int {
        val basePer60 = selectedSlot?.let { slot ->
            val cal = Calendar.getInstance().apply { timeInMillis = slot.startMillis }
            when (val h = cal.get(Calendar.HOUR_OF_DAY)) {
                in 9..15   -> 500
                in 16..18  -> 700
                in 19..20  -> 1000
                else       -> 0
            }
        } ?: 0

        val base = (basePer60 * durationMin) / 60
        val equipment = rackets * 200 + balls * 50
        return base + equipment
    }

    // Ažurira prikaz cene sa uvećanim fontom za broj i dodaje informaciju o iznajmljenoj opremi
    private fun updatePriceAndNote() {
        val price = calculatePrice(selectedDurationMin, rentedRackets, rentedBalls)
        val sb = SpannableStringBuilder()
        sb.append("Cena: ")
        val start = sb.length
        sb.append(price.toString())
        sb.setSpan(RelativeSizeSpan(1.2f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(" RSD")

        if (rentedRackets > 0 || rentedBalls > 0) {
            sb.append("\n")
            sb.append("Iznajmljeno: ")
            if (rentedRackets > 0) {
                sb.append("$rentedRackets reketa")
                if (rentedBalls > 0) sb.append(" i ")
            }
            if (rentedBalls > 0) {
                sb.append("$rentedBalls loptica")
            }
        }

        tvPrice.setSingleLine(false)
        tvPrice.maxLines = 3
        tvPrice.ellipsize = null
        tvPrice.text = sb
    }

    // Omogućava ili onemogućava dugme za rent equipment sa odgovarajućom alfa vrednošću
    private fun setRentEnabled(enabled: Boolean) {
        btnRentEquipment.isEnabled = enabled
        btnRentEquipment.alpha = if (enabled) 1f else 0.5f
    }

    // Validira podatke, kreira rezervaciju u Firestore-u za klub i korisnika, dodaje poene i navigira na profil
    private fun saveReservation() {
        val slot = selectedSlot ?: run {
            Toast.makeText(this, "Molimo izaberite termin", Toast.LENGTH_SHORT).show()
            return
        }

        val userId = auth.currentUser?.uid ?: run {
            Toast.makeText(this, "Morate biti prijavljeni", Toast.LENGTH_SHORT).show()
            return
        }

        btnReserve.isEnabled = false
        btnReserve.alpha = 0.5f

        val clubName = tvClubCourt.text.toString().substringBefore("•").trim()
        val courtName = tvClubCourt.text.toString().substringAfter("•").trim()

        val start = Date(slot.startMillis)
        val end = Date(slot.endMillis)

        val reservationData = hashMapOf(
            "userId" to userId,
            "clubId" to clubId,
            "courtId" to courtId,
            "clubName" to clubName,
            "courtName" to courtName,
            "startTime" to Timestamp(start),
            "endTime" to Timestamp(end),
            "durationMinutes" to selectedDurationMin,
            "players" to npPlayers.value,
            "totalPrice" to calculatePrice(selectedDurationMin, rentedRackets, rentedBalls),
            "rackets" to rentedRackets,
            "balls" to rentedBalls,
            "status" to "active",
            "createdAt" to Timestamp.now()
        )

        db.collection("clubs").document(clubId)
            .collection("courts").document(courtId)
            .collection("reservations")
            .add(reservationData)
            .addOnSuccessListener { docRef ->
                val userCopy = reservationData.toMutableMap().apply { put("id", docRef.id) }
                db.collection("users").document(userId)
                    .collection("reservations").document(docRef.id)
                    .set(userCopy)
                    .addOnCompleteListener {
                        val basePoints = pointsForDuration(selectedDurationMin)
                        val equipmentPoints = rentedRackets * 3 + rentedBalls * 1
                        val totalPoints = basePoints + equipmentPoints

                        val updates: MutableMap<String, Any> = hashMapOf(
                            "points" to FieldValue.increment(totalPoints.toLong()),
                            "updatedAt" to FieldValue.serverTimestamp(),
                            "stats.totalReservations" to FieldValue.increment(1L)
                        )

                        db.collection("users").document(userId)
                            .update(updates)
                            .addOnCompleteListener {
                                Toast.makeText(this, "Rezervacija uspešno kreirana!", Toast.LENGTH_LONG).show()
                                startActivity(
                                    Intent(this, ProfileActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                )
                                finish()
                            }
                    }
            }
            .addOnFailureListener { e ->
                btnReserve.isEnabled = true
                btnReserve.alpha = 1f
                Toast.makeText(this, "Greška: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    // Vraća broj poena za trajanje rezervacije (60min=5, 90min=11, 120min=12, 150min=18, 180min=23)
    private fun pointsForDuration(minutes: Int): Int = when (minutes) {
        60 -> 5; 90 -> 11; 120 -> 12; 150 -> 18; 180 -> 23
        else -> 0
    }

    // Kreira Calendar objekat za današnji dan sa vremenom postavljenim na ponoć (00:00:00.000)
    private fun todayAtMidnight(): Calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    // Formatira datum u "dd.MM.yyyy" format za prikaz
    private fun formatDateLabel(d: Date): String =
        SimpleDateFormat("dd.MM.yyyy", Locale("sr")).format(d)

    data class Window(val start: Long, val end: Long)
    data class Slot(val startMillis: Long, val endMillis: Long, val available: Boolean)

    class SlotsAdapter(private val onClick: (Slot, Int) -> Unit)
        : RecyclerView.Adapter<SlotsAdapter.VH>() {
        private val items = mutableListOf<Slot>()
        private var selectedPos: Int = RecyclerView.NO_POSITION

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tv: TextView = view.findViewById(R.id.tvSlot)
            init {
                view.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        onClick(items[pos], pos)
                    }
                }
            }
        }

        // Označava slot na datoj poziciji kao selektovan i osvežava prethodnu i novu selekciju
        fun setSelected(position: Int) {
            val old = selectedPos
            selectedPos = position
            if (old != RecyclerView.NO_POSITION) notifyItemChanged(old)
            notifyItemChanged(selectedPos)
        }

        // Ažurira listu slotova i opciono resetuje selekciju
        fun submit(data: List<Slot>, resetSelection: Boolean = false) {
            items.clear(); items.addAll(data)
            if (resetSelection) selectedPos = RecyclerView.NO_POSITION
            notifyDataSetChanged()
        }

        // Kreira ViewHolder inflating-om item_time_slot layout-a
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_time_slot, parent, false)
            return VH(v)
        }

        // Vraća broj slotova u listi
        override fun getItemCount(): Int = items.size

        // Prikazuje vreme slota, menja boju pozadine za dostupnost/selekciju i postavlja alpha za nedostupne slotove
        override fun onBindViewHolder(holder: VH, position: Int) {
            val it = items[position]
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            holder.tv.text = "${sdf.format(Date(it.startMillis))} — ${sdf.format(Date(it.endMillis))}"

            val ctx = holder.itemView.context
            val selected = position == selectedPos
            val bgRes = when {
                !it.available -> android.R.color.darker_gray
                selected      -> android.R.color.holo_blue_light
                else          -> android.R.color.white
            }
            holder.tv.setBackgroundColor(ContextCompat.getColor(ctx, bgRes))
            holder.tv.alpha = if (it.available) 1f else 0.5f
        }
    }

    companion object {
        const val EXTRA_CLUB_ID = "clubId"
        const val EXTRA_COURT_ID = "courtId"

        // Factory metod za pokretanje BookingActivity sa clubId i courtId parametrima
        fun start(context: Context, clubId: String, courtId: String) {
            val i = Intent(context, BookingActivity::class.java)
            i.putExtra(EXTRA_CLUB_ID, clubId)
            i.putExtra(EXTRA_COURT_ID, courtId)
            context.startActivity(i)
        }
    }
}
