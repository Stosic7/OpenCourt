package com.terenac.opencourt

import android.os.Bundle
import android.view.View
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import androidx.core.view.isVisible
import android.widget.LinearLayout
import android.widget.TextView
import com.google.firebase.firestore.FirebaseFirestore
import android.content.Intent
import com.google.firebase.firestore.SetOptions
import com.google.firebase.Timestamp
import java.text.Normalizer
import java.util.Locale
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.firestore.ListenerRegistration

enum class Kind { COURT_GRASS, COURT_CLAY, COURT_HARD, RACKET, BALL }

data class ShopItem(val title: String, val subtitle: String, val kind: Kind, val imageRes: Int)

class ExploreActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CLUB_NAME = "extra_club_name"
    }

    private lateinit var adapter: ShopAdapter
    private lateinit var loadingOverlay: View
    private val ui = Handler(Looper.getMainLooper())
    private var pendingLoader: Runnable? = null
    private lateinit var master: List<ShopItem>

    private val firestore by lazy { FirebaseFirestore.getInstance() }

    private var courtsListener: ListenerRegistration? = null
    private var unreadListener: ListenerRegistration? = null

    private lateinit var tvChatBadge: TextView

    // Konvertuje naziv kluba u URL-friendly slug format (lowercase, bez dijakritika, zamenjuje specijalne karaktere sa "-")
    private fun slugifyClub(name: String): String =
        Normalizer.normalize(name.lowercase(Locale.getDefault()), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .replace("[^a-z0-9]+".toRegex(), "-")
            .trim('-')

    // Vraća string reprezentaciju površine terena ("grass", "clay", "hard") na osnovu Kind enum-a
    private fun surfaceFromKind(kind: Kind): String = when (kind) {
        Kind.COURT_GRASS -> "grass"
        Kind.COURT_CLAY  -> "clay"
        Kind.COURT_HARD  -> "hard"
        else             -> "n/a"
    }

    // Inicijalizuje UI, postavlja bottom navigation, učitava terene/opremu, konfiguruje filtere, proverava vlasništvo kluba i omogućava dodavanje terena
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_explore)

        tvChatBadge = findViewById(R.id.tvChatBadge)
        // Postavlja real-time listener za nepročitane poruke iz svih chat-ova korisnika i ažurira badge sa brojem nepročitanih
        listenUnreadMessages()
        val btnChat = findViewById<MaterialButton>(R.id.btnChat)
        btnChat?.setOnClickListener {
            val uid = FirebaseAuth.getInstance().currentUser?.uid
            if (uid == null) {
                Toast.makeText(this, "Prijavite se da biste poslali poruku.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val clubName = intent.getStringExtra(EXTRA_CLUB_NAME) ?: run {
                Toast.makeText(this, "Nedostaje naziv kluba.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val db = FirebaseFirestore.getInstance()
            db.collection("clubs")
                .whereEqualTo("name", clubName)
                .limit(1)
                .get()
                .addOnSuccessListener { qs ->
                    val doc = qs.documents.firstOrNull()
                    if (doc == null) {
                        Toast.makeText(this, "Klub nije pronađen.", Toast.LENGTH_SHORT).show()
                        return@addOnSuccessListener
                    }

                    val clubId = doc.id
                    val adminId = doc.getString("ownerAdminId")
                    if (adminId.isNullOrBlank()) {
                        Toast.makeText(this, "Klub nema dodeljenog administratora.", Toast.LENGTH_SHORT).show()
                        return@addOnSuccessListener
                    }

                    if (uid == adminId) {
                        startActivity(Intent(this, MessagesHistory::class.java))
                    } else {
                        startActivity(
                            Intent(this, MessagesActivity::class.java)
                                .putExtra(MessagesActivity.EXTRA_CLUB_ID, clubId)
                                .putExtra(MessagesActivity.EXTRA_CLUB_NAME, clubName)
                                .putExtra(MessagesActivity.EXTRA_ADMIN_ID, adminId)
                                .putExtra(MessagesActivity.EXTRA_CONTACT_NAME, clubName)
                        )
                    }
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Greška pri čitanju kluba.", Toast.LENGTH_SHORT).show()
                }
        }

        val bottom = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
            R.id.bottom_navigation
        )
        bottom.selectedItemId = R.id.nav_map

        bottom.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_map -> true
                R.id.nav_sports -> {
                    startActivity(Intent(this, AdvertisementActivity::class.java))
                    finish()
                    true
                }
                R.id.nav_profile -> {
                    startActivity(Intent(this, ProfileActivity::class.java))
                    finish()
                    true
                }
                else -> false
            }
        }

        bottom.setOnItemReselectedListener { item ->
            if (item.itemId == R.id.nav_map) {
                if (!isTaskRoot) {
                    finish()
                } else {
                    val i = Intent(this, HomeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    startActivity(i)
                }
            }
        }

        loadingOverlay = findViewById(R.id.loadingOverlay)

        val tvWelcomeLabel = findViewById<TextView>(R.id.tvWelcomeLabel)
        val tvUserName     = findViewById<TextView>(R.id.tvUserName)
        val btnBack        = findViewById<MaterialButton>(R.id.btnBack)

        val bottomNav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_navigation)
        val rv = findViewById<RecyclerView>(R.id.recyclerGrid)
        rv.layoutManager = GridLayoutManager(this, 2)
        rv.setHasFixedSize(true)

        rv.clipToPadding = false
        rv.post {
            val extra = (rv.resources.displayMetrics.density * 24).toInt()
            rv.setPadding(
                rv.paddingLeft,
                rv.paddingTop,
                rv.paddingRight,
                rv.paddingBottom + bottomNav.height + extra
            )
        }

        val chipAll     = findViewById<LinearLayout>(R.id.chipAll)
        val chipCourts  = findViewById<LinearLayout>(R.id.chipCourts)
        val chipRackets = findViewById<LinearLayout>(R.id.chipRackets)
        val chipBalls   = findViewById<LinearLayout>(R.id.chipBalls)

        val fabAdd = findViewById<FloatingActionButton>(R.id.fabAdd)
        fabAdd.isVisible = false

        val clubName = intent.getStringExtra(EXTRA_CLUB_NAME)

        if (clubName.isNullOrBlank()) {
            // Vraća ime korisnika iz displayName, email-a ili Intent extra parametra, fallback je "Korisniče"
            tvUserName.text = " ${resolveUserName()}"
            master = listOf(
                ShopItem("Teren (trava)",  "Iznajmi termin", Kind.COURT_GRASS, R.drawable.court_grass),
                ShopItem("Teren (šljaka)", "Iznajmi termin", Kind.COURT_CLAY,  R.drawable.court_clay),
                ShopItem("Teren (beton)",  "Iznajmi termin", Kind.COURT_HARD,  R.drawable.court_hard),
                ShopItem("Reket",          "Dostupno",       Kind.RACKET,      R.drawable.tennis_racket),
                ShopItem("Loptice",        "Dostupno",       Kind.BALL,        R.drawable.tennis_ball),
                ShopItem("Teren (trava)",  "Iznajmi termin", Kind.COURT_GRASS, R.drawable.court_grass)
            )
            adapter = ShopAdapter(master.toMutableList())
            rv.adapter = adapter

            fun select(all:Boolean=false, courts:Boolean=false, rackets:Boolean=false, balls:Boolean=false) {
                chipAll.setBackgroundResource(if (all) R.drawable.tab_chip_selected else R.drawable.tab_chip)
                chipCourts.setBackgroundResource(if (courts) R.drawable.tab_chip_selected else R.drawable.tab_chip)
                chipRackets.setBackgroundResource(if (rackets) R.drawable.tab_chip_selected else R.drawable.tab_chip)
                chipBalls.setBackgroundResource(if (balls) R.drawable.tab_chip_selected else R.drawable.tab_chip)
            }
            fun applyFilter(kind: Kind?) {
                val list = if (kind == null) master else master.filter { it.kind == kind }
                adapter.submit(list)
            }
            chipAll.setOnClickListener     { select(all=true);     applyFilter(null) }
            chipCourts.setOnClickListener  { select(courts=true);  applyFilter(Kind.COURT_GRASS) }
            chipRackets.setOnClickListener { select(rackets=true); applyFilter(Kind.RACKET) }
            chipBalls.setOnClickListener   { select(balls=true);   applyFilter(Kind.BALL) }
            select(all=true); applyFilter(null)

            btnBack.visibility = View.GONE
            fabAdd.isVisible = false
            courtsListener?.remove(); courtsListener = null

        } else {
            tvWelcomeLabel.text = clubName
            tvUserName.text = ""
            btnBack.visibility = View.VISIBLE
            btnBack.setOnClickListener {
                showLoadingIfSlow()
                finish()
                ui.post { cancelPendingLoader() }
            }

            chipBalls.visibility = View.GONE

            // Generiše listu terena za specifični klub na osnovu predefinisanih podataka i čuva ih u Firestore courts subkolekciju
            master = courtsFor(clubName)
            adapter = ShopAdapter(master.toMutableList()) { _, position ->
                val clubIdSlug = slugifyClub(clubName)
                val courtId = "court-${position + 1}"
                BookingActivity.start(this@ExploreActivity, clubIdSlug, courtId)
            }
            rv.adapter = adapter

            fun refreshSurfaceChips() {
                val hasClay  = master.any { it.kind == Kind.COURT_CLAY }
                val hasHard  = master.any { it.kind == Kind.COURT_HARD }
                val hasGrass = master.any { it.kind == Kind.COURT_GRASS }

                // Pronalazi TextView unutar chip LinearLayout-a i postavlja mu tekst
                setChipText(chipAll, "Sve")

                // repurposeChipAsText uklanja sve child view-ove iz LinearLayout-a i dodaje novi TextView sa zadatim tekstom i stilom
                if (hasClay) { repurposeChipAsText(chipCourts, "Šljaka"); chipCourts.visibility = View.VISIBLE }
                else         { chipCourts.visibility = View.GONE }

                if (hasHard) { repurposeChipAsText(chipRackets, "Beton"); chipRackets.visibility = View.VISIBLE }
                else         { chipRackets.visibility = View.GONE }

                if (hasGrass){ repurposeChipAsText(chipBalls, "Trava"); chipBalls.visibility = View.VISIBLE }
                else         { chipBalls.visibility = View.GONE }

                fun select(all:Boolean=false, clay:Boolean=false, hard:Boolean=false, grass:Boolean=false) {
                    chipAll.setBackgroundResource(if (all) R.drawable.tab_chip_selected else R.drawable.tab_chip)
                    chipCourts.setBackgroundResource(if (clay) R.drawable.tab_chip_selected else R.drawable.tab_chip)
                    chipRackets.setBackgroundResource(if (hard) R.drawable.tab_chip_selected else R.drawable.tab_chip)
                    chipBalls.setBackgroundResource(if (grass) R.drawable.tab_chip_selected else R.drawable.tab_chip)
                }
                fun apply(kind: Kind?) {
                    val list = if (kind == null) master else master.filter { it.kind == kind }
                    adapter.submit(list)
                }

                chipAll.setOnClickListener    { select(all = true);  apply(null) }
                if (hasClay)  chipCourts.setOnClickListener  { showLoadingIfSlow(); select(clay  = true); apply(Kind.COURT_CLAY);  ui.post { cancelPendingLoader() } }
                if (hasHard)  chipRackets.setOnClickListener { showLoadingIfSlow(); select(hard  = true); apply(Kind.COURT_HARD);  ui.post { cancelPendingLoader() } }
                if (hasGrass) chipBalls.setOnClickListener   { showLoadingIfSlow(); select(grass = true); apply(Kind.COURT_GRASS); ui.post { cancelPendingLoader() } }

                select(all = true); apply(null)
            }

            refreshSurfaceChips()

            val uid = FirebaseAuth.getInstance().currentUser?.uid
            firestore.collection("clubs")
                .whereEqualTo("name", clubName)
                .limit(1)
                .get()
                .addOnSuccessListener { qs ->
                    val doc = qs.documents.firstOrNull()
                    val clubDocId = doc?.id

                    if (clubDocId == null) {
                        fabAdd.isVisible = false
                        courtsListener?.remove(); courtsListener = null
                        return@addOnSuccessListener
                    }

                    val owner = doc.getString("ownerAdminId")
                    fabAdd.isVisible = (uid != null && uid == owner)

                    fabAdd.setOnClickListener {
                        val itn = Intent(this, AddTenisCourtActivity::class.java)
                            .putExtra(AddTenisCourtActivity.EXTRA_CLUB_ID, clubDocId)
                        startActivity(itn)
                    }

                    courtsListener?.remove()
                    courtsListener = firestore.collection("clubs").document(clubDocId)
                        .collection("courts")
                        .addSnapshotListener { snap, err ->
                            if (err != null) return@addSnapshotListener
                            val docs = snap?.documents.orEmpty()
                            if (docs.isEmpty()) return@addSnapshotListener

                            val remoteList = docs.sortedBy { it.getLong("index") ?: Long.MAX_VALUE }
                                .mapIndexed { idx, d ->
                                    val title = d.getString("name") ?: "Teren ${idx + 1}"
                                    val surface = (d.getString("surface") ?: "").lowercase(Locale.getDefault())
                                    val (label, img, kind) = when (surface) {
                                        "clay"  -> Triple("Šljaka • napolju", R.drawable.court_clay,  Kind.COURT_CLAY)
                                        "hard"  -> Triple("Beton • napolju",  R.drawable.court_hard,  Kind.COURT_HARD)
                                        "grass" -> Triple("Trava • napolju",  R.drawable.court_grass, Kind.COURT_GRASS)
                                        else    -> Triple("Teren",             R.drawable.court_hard,  Kind.COURT_HARD)
                                    }
                                    ShopItem(title, label, kind, img)
                                }

                            master = remoteList
                            adapter.submit(master)

                            refreshSurfaceChips()
                        }
                }
                .addOnFailureListener {
                    fabAdd.isVisible = false
                    courtsListener?.remove(); courtsListener = null
                }
        }
    }

    private fun listenUnreadMessages() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        unreadListener?.remove()
        unreadListener = firestore.collection("chats")
            .whereArrayContains("users", uid)
            .addSnapshotListener { snap, _ ->
                var totalUnread = 0
                snap?.documents?.forEach { d ->
                    val unreadFor = d.get("unreadFor") as? Map<*, *> ?: emptyMap<String, Int>()
                    val unread = (unreadFor[uid] as? Number)?.toInt() ?: 0
                    totalUnread += unread
                }

                if (totalUnread > 0) {
                    tvChatBadge.text = if (totalUnread > 99) "99+" else totalUnread.toString()
                    tvChatBadge.visibility = View.VISIBLE
                } else {
                    tvChatBadge.visibility = View.GONE
                }
            }
    }

    private fun resolveUserName(): String {
        val u = FirebaseAuth.getInstance().currentUser
        u?.displayName?.takeIf { !it.isNullOrBlank() }?.let { return it }
        u?.email?.substringBefore('@')?.takeIf { it.isNotBlank() }?.let { return it }
        intent.getStringExtra("user_name")?.takeIf { it.isNotBlank() }?.let { return it }
        return "Korisniče"
    }

    private fun courtsFor(clubName: String): List<ShopItem> {
        data class Segment(val count: Int, val kind: Kind, val subtitle: String, val image: Int)

        val segments: List<Segment> = when (clubName) {
            "Teniska Akademija Živković" -> listOf(
                Segment(6, Kind.COURT_CLAY, "Šljaka • napolju",  R.drawable.court_clay),
                Segment(3, Kind.COURT_CLAY, "Šljaka • unutra",   R.drawable.court_clay),
                Segment(1, Kind.COURT_HARD, "Beton",             R.drawable.court_hard)
            )
            "Teniski Klub Kostić" -> listOf(
                Segment(4, Kind.COURT_CLAY, "Šljaka • napolju",  R.drawable.court_clay)
            )
            "Teniski Klub Radnički" -> listOf(
                Segment(9, Kind.COURT_CLAY, "Šljaka • napolju",  R.drawable.court_clay)
            )
            "Teniski Klub Niška Banja" -> listOf(
                Segment(2, Kind.COURT_CLAY, "Šljaka • napolju",  R.drawable.court_clay),
                Segment(1, Kind.COURT_HARD, "Beton",             R.drawable.court_hard)
            )
            else -> emptyList()
        }

        val out = ArrayList<ShopItem>()

        val clubId = slugifyClub(clubName)
        val courtsColl = firestore.collection("clubs").document(clubId).collection("courts")

        var nextIndex = 1
        for (seg in segments) {
            repeat(seg.count) {
                val title = "Teren $nextIndex"
                val indoor = seg.subtitle.lowercase(Locale.getDefault()).contains("unutra")
                val courtId = "court-$nextIndex"

                val courtData = mapOf(
                    "name" to title,
                    "kind" to seg.kind.name,
                    "surface" to surfaceFromKind(seg.kind),
                    "indoor" to indoor,
                    "index" to nextIndex,
                    "subtitle" to seg.subtitle,
                    "createdAt" to Timestamp.now()
                )
                courtsColl.document(courtId).set(courtData, SetOptions.merge())
                nextIndex++
            }
        }

        var idx = 1
        for (seg in segments) {
            repeat(seg.count) {
                out += ShopItem("Teren $idx", seg.subtitle, seg.kind, seg.image)
                idx++
            }
        }
        return out
    }

    private fun setChipText(container: LinearLayout, label: String) {
        val tvChild = (0 until container.childCount)
            .asSequence()
            .map { container.getChildAt(it) }
            .firstOrNull { it is TextView } as? TextView

        if (tvChild != null) {
            tvChild.text = label
        } else {
            repurposeChipAsText(container, label)
        }
    }

    private fun repurposeChipAsText(container: LinearLayout, label: String) {
        container.removeAllViews()
        val tv = TextView(container.context).apply {
            text = label
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            paint.isFakeBoldText = true
            val scale = resources.displayMetrics.density
            val pStart = (16 * scale).toInt()
            val pEnd   = (16 * scale).toInt()
            setPadding(pStart, 0, pEnd, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (40 * scale).toInt()
            )
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        container.addView(tv)
    }

    // Prikazuje ili sakriva loading overlay
    private fun setLoading(loading: Boolean) {
        loadingOverlay.isVisible = loading
    }

    // Zakazuje prikaz loading overlay-a nakon zadatog delay-a (default 150ms) ako operacija još uvek traje
    private fun showLoadingIfSlow(timeoutMs: Long = 150) {
        cancelPendingLoader()
        pendingLoader = Runnable { setLoading(true) }
        ui.postDelayed(pendingLoader!!, timeoutMs)
    }

    // Otkazuje zakazani loading callback i odmah sakriva loading overlay
    private fun cancelPendingLoader() {
        pendingLoader?.let { ui.removeCallbacks(it) }
        pendingLoader = null
        setLoading(false)
    }

    // Čisti sve Firestore listenere i otkazuje pending callback-ove da spreči memory leak
    override fun onDestroy() {
        super.onDestroy()
        cancelPendingLoader()
        courtsListener?.remove()
        courtsListener = null
        unreadListener?.remove()
        unreadListener = null
    }
}
