package com.terenac.opencourt

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.os.Build
import android.os.Bundle
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

data class LeaderboardEntry(
    val userId: String = "",
    val userName: String = "",
    val points: Int = 0,
    val totalReservations: Int = 0,
    val rank: Int = 0,
    val isCurrentUser: Boolean = false,
    val isAdmin: Boolean = false
)

class LeaderBoardActivity : AppCompatActivity() {

    private lateinit var rvLeaderboard: RecyclerView
    private lateinit var btnBack: MaterialButton
    private lateinit var loadingOverlay: View
    private lateinit var tvFirstName: TextView
    private lateinit var tvFirstScore: TextView
    private lateinit var tvSecondName: TextView
    private lateinit var tvSecondScore: TextView
    private lateinit var tvThirdName: TextView
    private lateinit var tvThirdScore: TextView
    private lateinit var leaderboardAdapter: LeaderboardAdapter

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    // Inicijalizuje UI komponente, postavlja top 3 TextView-ove, RecyclerView i učitava leaderboard iz Firestore-a
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_leader_board)

        initViews()
        setupTop3TextViews()
        setupRecyclerView()
        loadLeaderboard()

        btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    // Pronalazi i inicijalizuje sve UI komponente (RecyclerView, dugmad, TextViews za top 3, loading overlay)
    private fun initViews() {
        rvLeaderboard = findViewById(R.id.rvLeaderboard)
        btnBack = findViewById(R.id.btnBack)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        tvFirstName = findViewById(R.id.tvFirstName)
        tvFirstScore = findViewById(R.id.tvFirstScore)
        tvSecondName = findViewById(R.id.tvSecondName)
        tvSecondScore = findViewById(R.id.tvSecondScore)
        tvThirdName = findViewById(R.id.tvThirdName)
        tvThirdScore = findViewById(R.id.tvThirdScore)
    }

    // Konfigurira TextViews za top 3 igrača sa auto-sizing fontom, centriranim tekstom i word wrapping-om
    private fun setupTop3TextViews() {
        fun tune(tv: TextView) {
            tv.gravity = Gravity.CENTER_HORIZONTAL
            tv.textAlignment = View.TEXT_ALIGNMENT_CENTER
            tv.setSingleLine(false)
            tv.maxLines = 2
            tv.ellipsize = null
            tv.includeFontPadding = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                tv.breakStrategy = LineBreaker.BREAK_STRATEGY_HIGH_QUALITY
                tv.hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NORMAL
            }
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                tv,
                /* min */ 12, /* max */ 18, /* step */ 1,
                /* unit */ android.util.TypedValue.COMPLEX_UNIT_SP
            )
        }

        tune(tvFirstName)
        tune(tvSecondName)
        tune(tvThirdName)
    }

    // Inicijalizuje LeaderboardAdapter i postavlja LinearLayoutManager za RecyclerView
    private fun setupRecyclerView() {
        leaderboardAdapter = LeaderboardAdapter()
        rvLeaderboard.layoutManager = LinearLayoutManager(this)
        rvLeaderboard.adapter = leaderboardAdapter
    }

    // Učitava sve korisnike iz Firestore-a, sortira po poenima, dodeljuje rangove i prikazuje top 3 i ostatak liste
    private fun loadLeaderboard() {
        setLoading(true)

        db.collection("users").get()
            .addOnSuccessListener { usersSnapshot ->
                val currentUid = auth.currentUser?.uid
                val entries = usersSnapshot.documents.map { doc ->
                    val uid = doc.id
                    val firstName = (doc.getString("firstName") ?: "").trim()
                    val lastName = (doc.getString("lastName") ?: "").trim()
                    val displayName = listOf(firstName, lastName).filter { it.isNotBlank() }
                        .joinToString(" ")
                        .ifBlank { doc.getString("email")?.substringBefore('@') ?: "Korisnik" }

                    val points = (doc.getLong("points") ?: 0L).toInt()
                    val totalRes = (doc.get("stats.totalReservations") as? Number)?.toInt() ?: 0
                    val isAdmin = doc.getBoolean("isAdmin") == true

                    LeaderboardEntry(
                        userId = uid,
                        userName = displayName,
                        points = points,
                        totalReservations = totalRes,
                        isCurrentUser = uid == currentUid,
                        isAdmin = isAdmin
                    )
                }
                    .sortedByDescending { it.points }
                    .mapIndexed { index, e -> e.copy(rank = index + 1) }

                setLoading(false)

                val first = entries.getOrNull(0)
                val second = entries.getOrNull(1)
                val third = entries.getOrNull(2)

                tvFirstName.text = formatNameWithAdmin(first?.userName ?: "—", first?.isAdmin == true)
                tvFirstScore.text = "${first?.points ?: 0} 🏆"

                tvSecondName.text = formatNameWithAdmin(second?.userName ?: "—", second?.isAdmin == true)
                tvSecondScore.text = "${second?.points ?: 0} 🏆"

                tvThirdName.text = formatNameWithAdmin(third?.userName ?: "—", third?.isAdmin == true)
                tvThirdScore.text = "${third?.points ?: 0} 🏆"

                leaderboardAdapter.submitList(entries)
            }
            .addOnFailureListener {
                setLoading(false)
            }
    }

    // Formatira ime sa "Administrator" labelom u zlatnoj boji ispod imena ako je korisnik admin
    private fun formatNameWithAdmin(name: String, isAdmin: Boolean): CharSequence {
        if (!isAdmin) return name
        val gold = Color.parseColor("#FFD700")
        val label = "Administrator"
        val sb = SpannableStringBuilder()
            .append(name)
            .append("\n")
            .append(label)
        val start = sb.length - label.length
        val end = sb.length
        sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(RelativeSizeSpan(0.85f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(gold), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    // Prikazuje ili sakriva loading overlay
    private fun setLoading(loading: Boolean) {
        loadingOverlay.isVisible = loading
    }
}
