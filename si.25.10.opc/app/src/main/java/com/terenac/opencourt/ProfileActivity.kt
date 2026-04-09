package com.terenac.opencourt

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatButton
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import android.text.InputType

data class UserReservation(
    val id: String = "",
    val clubName: String = "",
    val courtName: String = "",
    val startTime: Timestamp? = null,
    val endTime: Timestamp? = null,
    val players: Int = 0,
    val durationMinutes: Int = 0,
    val totalPrice: Int = 0,
    val rackets: Int = 0,
    val balls: Int = 0,
    val status: String = "active"
)

class ProfileActivity : AppCompatActivity() {

    private lateinit var tvFullName: TextView
    private lateinit var tvEmail: TextView
    private lateinit var tvFirstName: TextView
    private lateinit var tvLastName: TextView
    private lateinit var tvPhone: TextView
    private lateinit var rvReservations: RecyclerView
    private lateinit var tvNoReservations: TextView
    private lateinit var btnLogout: AppCompatButton
    private lateinit var btnLeaderboard: MaterialButton
    private lateinit var loadingOverlay: View
    private lateinit var btnEditProfile: AppCompatButton
    private lateinit var btnChangePassword: AppCompatButton
    private var tvAdminBadge: TextView? = null

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }
    private lateinit var reservationsAdapter: ReservationsAdapter
    private var allReservations: List<UserReservation> = emptyList()
    private val tickerHandler = android.os.Handler()
    private var tickerRunnable: Runnable? = null

    // Inicijalizuje UI komponente, postavlja bottom navigation, dugmad, RecyclerView i učitava korisničke podatke i rezervacije
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        initViews()
        setupBottomNavigation()
        setupButtons()
        setupRecyclerView()
        loadUserData()
        loadUserReservations()
    }

    // Zaustavlja ticker za automatsko uklanjanje isteklih rezervacija da spreči memory leak
    override fun onDestroy() {
        super.onDestroy()
        stopExpiryTicker()
    }

    // Pronalazi i inicijalizuje sve UI komponente
    private fun initViews() {
        tvFullName = findViewById(R.id.tvFullName)
        tvEmail = findViewById(R.id.tvEmail)
        tvFirstName = findViewById(R.id.tvFirstName)
        tvLastName = findViewById(R.id.tvLastName)
        tvPhone = findViewById(R.id.tvPhone)
        rvReservations = findViewById(R.id.rvReservations)
        tvNoReservations = findViewById(R.id.tvNoReservations)
        btnLogout = findViewById(R.id.btnLogout)
        btnLeaderboard = findViewById(R.id.btnLeaderboard)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        btnEditProfile = findViewById(R.id.btnEditProfile)
        btnChangePassword = findViewById(R.id.btnChangePassword)
    }

    // Postavlja bottom navigation sa navigacijom između ekrana i smooth scroll na reselect
    private fun setupBottomNavigation() {
        val bottom = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottom.selectedItemId = R.id.nav_profile

        bottom.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_map -> {
                    val intent = Intent(this, HomeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    startActivity(intent)
                    finish()
                    true
                }
                R.id.nav_sports -> {
                    val intent = Intent(this, AdvertisementActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    startActivity(intent)
                    finish()
                    true
                }
                R.id.nav_profile -> {
                    true
                }
                else -> false
            }
        }

        bottom.setOnItemReselectedListener { item ->
            if (item.itemId == R.id.nav_profile) {
                findViewById<ScrollView>(R.id.scrollContent)?.smoothScrollTo(0, 0)
            }
        }
    }

    // Postavlja click listenere za logout, leaderboard, edit profile i change password dugmad
    private fun setupButtons() {
        btnLogout.setOnClickListener {
            auth.signOut()
            val intent = Intent(this, StartingScreenActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
            finish()
        }

        btnLeaderboard.setOnClickListener {
            startActivity(Intent(this, LeaderBoardActivity::class.java))
        }

        btnEditProfile.setOnClickListener {
            showEditPhoneDialog()
        }

        btnChangePassword.setOnClickListener {
            showChangePasswordDialog()
        }
    }

    // Inicijalizuje ReservationsAdapter i postavlja LinearLayoutManager za RecyclerView
    private fun setupRecyclerView() {
        reservationsAdapter = ReservationsAdapter { reservation ->
            Toast.makeText(this, "Rezervacija: ${reservation.clubName}", Toast.LENGTH_SHORT).show()
        }
        rvReservations.layoutManager = LinearLayoutManager(this)
        rvReservations.adapter = reservationsAdapter
    }

    // Učitava korisničke podatke iz Firestore-a (ime, prezime, email, telefon) i prikazuje admin badge ako je potrebno
    private fun loadUserData() {
        setLoading(true)
        val userId = auth.currentUser?.uid ?: run {
            setLoading(false)
            return
        }

        db.collection("users").document(userId).get()
            .addOnSuccessListener { document ->
                setLoading(false)
                var isAdmin = false
                if (document.exists()) {
                    val firstName = document.getString("firstName") ?: ""
                    val lastName = document.getString("lastName") ?: ""
                    val email = document.getString("email") ?: auth.currentUser?.email ?: ""
                    val phone = document.getString("phone") ?: "Nije uneto"
                    isAdmin = document.getBoolean("isAdmin") == true

                    tvFullName.text = "$firstName $lastName".trim()
                    tvEmail.text = email
                    tvFirstName.text = firstName
                    tvLastName.text = lastName
                    tvPhone.text = phone
                } else {
                    val displayName = auth.currentUser?.displayName ?: "Korisnik"
                    val names = displayName.split(" ")
                    tvFullName.text = displayName
                    tvEmail.text = auth.currentUser?.email ?: ""
                    tvFirstName.text = names.getOrNull(0) ?: ""
                    tvLastName.text = names.getOrNull(1) ?: ""
                    tvPhone.text = "Nije uneto"
                }

                renderAdminBadgeIfNeeded(isAdmin)
            }
            .addOnFailureListener {
                setLoading(false)
                val displayName = auth.currentUser?.displayName ?: "Korisnik"
                val names = displayName.split(" ")
                tvFullName.text = displayName
                tvEmail.text = auth.currentUser?.email ?: ""
                tvFirstName.text = names.getOrNull(0) ?: ""
                tvLastName.text = names.getOrNull(1) ?: ""
                tvPhone.text = "Nije uneto"

                renderAdminBadgeIfNeeded(false)
            }
    }

    // Prikazuje zlatni "Administrator" badge ispod imena ako je korisnik admin
    private fun renderAdminBadgeIfNeeded(isAdmin: Boolean) {
        val parent = tvFullName.parent as? LinearLayout ?: return
        if (isAdmin) {
            if (tvAdminBadge == null) {
                tvAdminBadge = TextView(this).apply {
                    text = "Administrator"
                    setTextColor(Color.parseColor("#FFD700"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTypeface(typeface, Typeface.BOLD)
                    setShadowLayer(8f, 0f, 0f, Color.parseColor("#80FFD700"))
                }
                val indexOfFullName = parent.indexOfChild(tvFullName)
                parent.addView(tvAdminBadge, indexOfFullName + 1)
            } else {
                tvAdminBadge?.visibility = View.VISIBLE
            }
        } else {
            tvAdminBadge?.visibility = View.GONE
        }
    }

    // Postavlja real-time listener za korisničke rezervacije iz Firestore users/reservations subkolekcije
    private fun loadUserReservations() {
        val userId = auth.currentUser?.uid ?: run {
            Log.e("ProfileActivity", "User not logged in")
            showEmptyState(true)
            return
        }

        db.collection("users").document(userId)
            .collection("reservations")
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.e("ProfileActivity", "Reservations listen error", err)
                    allReservations = emptyList()
                    applyReservationsFilterAndRender()
                    return@addSnapshotListener
                }

                val list = snap?.documents?.mapNotNull { d ->
                    try {
                        UserReservation(
                            id = d.getString("id") ?: d.id,
                            clubName = d.getString("clubName") ?: "",
                            courtName = d.getString("courtName") ?: "",
                            startTime = d.getTimestamp("startTime"),
                            endTime = d.getTimestamp("endTime"),
                            players = (d.getLong("players") ?: 0L).toInt(),
                            durationMinutes = (d.getLong("durationMinutes") ?: 0L).toInt(),
                            totalPrice = (d.getLong("totalPrice") ?: 0L).toInt(),
                            rackets = (d.getLong("rackets") ?: 0L).toInt(),
                            balls = (d.getLong("balls") ?: 0L).toInt(),
                            status = d.getString("status") ?: "active"
                        )
                    } catch (_: Exception) {
                        null
                    }
                } ?: emptyList()

                allReservations = list
                applyReservationsFilterAndRender()
            }
    }

    // Filtrira aktivne rezervacije (ne-cancelled, endTime u budućnosti), sortira po startTime i zakazuje ticker za automatsko uklanjanje isteklih
    private fun applyReservationsFilterAndRender() {
        val nowMs = System.currentTimeMillis()

        val active = allReservations
            .filter { it.status.lowercase() != "cancelled" }
            .filter { res ->
                val end = res.endTime?.toDate()?.time
                end != null && end > nowMs
            }
            .sortedBy { it.startTime?.toDate()?.time ?: Long.MAX_VALUE }

        reservationsAdapter.submitList(active)
        showEmptyState(active.isEmpty())

        scheduleExpiryTick(active)
    }

    // Zakazuje ticker koji će automatski osvežiti listu kada najranija rezervacija istekne
    private fun scheduleExpiryTick(active: List<UserReservation>) {
        stopExpiryTicker()
        val nextEnd = active.minOfOrNull { it.endTime?.toDate()?.time ?: Long.MAX_VALUE } ?: return
        val delay = (nextEnd - System.currentTimeMillis()).coerceAtLeast(0L)
        if (delay == Long.MAX_VALUE || delay == 0L) return

        tickerRunnable = Runnable {
            applyReservationsFilterAndRender()
        }
        tickerHandler.postDelayed(tickerRunnable!!, delay + 50L)
    }

    // Prikazuje ili sakriva "Nemate aktivnih rezervacija" poruku i RecyclerView
    private fun showEmptyState(empty: Boolean) {
        tvNoReservations.isVisible = empty
        rvReservations.isVisible = !empty
    }

    // Zaustavlja zakazani expiry ticker i uklanja callback
    private fun stopExpiryTicker() {
        tickerRunnable?.let { tickerHandler.removeCallbacks(it) }
        tickerRunnable = null
    }

    // Prikazuje ili sakriva loading overlay
    private fun setLoading(loading: Boolean) {
        loadingOverlay.isVisible = loading
    }

    // Konvertuje dp vrednost u piksele na osnovu gustine ekrana
    private fun dp(x: Int): Int =
        (x * resources.displayMetrics.density).toInt()

    // Kreira gradient pozadinu za dijaloge sa zaobljenim uglovima i stroke-om
    private fun makeDialogBackground(): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.parseColor("#1B3341"),
                Color.parseColor("#2C8AC7")
            )
        ).apply {
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), Color.parseColor("#33FFFFFF"))
        }

    // Stilizuje EditText sa tamnom pozadinom, belim tekstom i zaobljenim uglovima
    private fun styleEditText(et: EditText) {
        et.setTextColor(Color.WHITE)
        et.setHintTextColor(Color.parseColor("#80FFFFFF"))
        et.setPadding(dp(14), dp(12), dp(14), dp(12))
        et.background = GradientDrawable().apply {
            setColor(Color.parseColor("#162A35"))
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), Color.parseColor("#264B5D"))
        }
    }

    // Stilizuje positive i negative dugmad dijaloga sa gradientom i belim tekstom
    private fun styleDialogButtons(dialog: AlertDialog) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.let { b ->
            b.setTextColor(Color.WHITE)
            b.background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    Color.parseColor("#40B0FF"),
                    Color.parseColor("#2D7DF6")
                )
            ).apply { cornerRadius = dp(16).toFloat() }
            b.setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.let { b ->
            b.setTextColor(Color.parseColor("#B3FFFFFF"))
        }
    }

    // Kreira stilizovani naslov za dijaloge sa belim bold tekstom
    private fun makeThemedTitle(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

    // Prikazuje dijalog za izmenu broja telefona i čuva promenu u Firestore
    private fun showEditPhoneDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(12))
            background = makeDialogBackground()
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            hint = "Unesite novi telefon"
            setText(tvPhone.text)
            styleEditText(this)
        }
        container.addView(input)

        val dialog = AlertDialog.Builder(this)
            .setCustomTitle(makeThemedTitle("Izmena telefona"))
            .setView(container)
            .setPositiveButton("Sačuvaj", null)
            .setNegativeButton("Otkaži", null)
            .create()

        dialog.setOnShowListener {
            styleDialogButtons(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val newPhone = input.text.toString().trim()
                val uid = auth.currentUser?.uid
                if (newPhone.isNotBlank() && uid != null) {
                    setLoading(true)
                    db.collection("users").document(uid)
                        .update(mapOf("phone" to newPhone))
                        .addOnCompleteListener {
                            setLoading(false)
                            tvPhone.text = newPhone
                            Toast.makeText(this, "Sačuvano", Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                        }
                } else {
                    Toast.makeText(this, "Unesite ispravan broj", Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.window?.setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
        dialog.show()
    }

    // Prikazuje dijalog za promenu šifre sa reauthentication-om, validira unos (min 6 karaktera) i ažurira šifru kroz Firebase Authentication
    private fun showChangePasswordDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(12))
            background = makeDialogBackground()
        }

        val inputCurrent = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Trenutna šifra"
            styleEditText(this)
        }
        container.addView(inputCurrent)

        val spacer1 = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(12)
            )
        }
        container.addView(spacer1)

        val inputNew = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Nova šifra (min 6 karaktera)"
            styleEditText(this)
        }
        container.addView(inputNew)

        val dialog = AlertDialog.Builder(this)
            .setCustomTitle(makeThemedTitle("Promena šifre"))
            .setView(container)
            .setPositiveButton("Promeni", null)
            .setNegativeButton("Otkaži", null)
            .create()

        dialog.setOnShowListener {
            styleDialogButtons(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val currentPass = inputCurrent.text.toString().trim()
                val newPass = inputNew.text.toString().trim()

                if (currentPass.isEmpty()) {
                    Toast.makeText(this, "Unesite trenutnu šifru", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                if (newPass.length < 6) {
                    Toast.makeText(this, "Nova šifra mora imati bar 6 karaktera", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val user = auth.currentUser
                val email = user?.email

                if (user == null || email == null) {
                    Toast.makeText(this, "Greška: korisnik nije prijavljen", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                setLoading(true)

                val credential = EmailAuthProvider.getCredential(email, currentPass)
                user.reauthenticate(credential)
                    .addOnSuccessListener {
                        user.updatePassword(newPass)
                            .addOnSuccessListener {
                                setLoading(false)
                                Toast.makeText(this, "Šifra uspešno promenjena", Toast.LENGTH_SHORT).show()
                                dialog.dismiss()
                            }
                            .addOnFailureListener { e ->
                                setLoading(false)
                                Toast.makeText(this, "Greška pri promeni šifre: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                            }
                    }
                    .addOnFailureListener { e ->
                        setLoading(false)
                        val errorMsg = when {
                            e.message?.contains("wrong-password") == true || e.message?.contains("invalid-credential") == true ->
                                "Pogrešna trenutna šifra"
                            else -> "Greška pri autentifikaciji: ${e.localizedMessage}"
                        }
                        Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
                    }
            }
        }
        dialog.window?.setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
        dialog.show()
    }
}
