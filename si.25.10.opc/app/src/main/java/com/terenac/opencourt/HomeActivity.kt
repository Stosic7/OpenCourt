package com.terenac.opencourt

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.location.Location
import androidx.core.view.isVisible
import android.os.Bundle
import android.os.SystemClock
import com.google.firebase.firestore.SetOptions
import com.google.firebase.Timestamp
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.round

// DODATO: FAB (opciono, ako postoji u XML-u)
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.firestore.ListenerRegistration

data class Club(
    val name: String,
    val distanceMeters: Int,
    val thumbDataUrl: String? = null
)

private data class Place(
    val name: String,
    val lat: Double,
    val lng: Double,
    val thumbDataUrl: String? = null
)

class HomeActivity : AppCompatActivity() {

    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private var didSyncClubs = false

    // Konvertuje naziv kluba u URL-friendly slug format za Firestore document ID (lowercase, bez dijakritika, zamenjuje specijalne karaktere sa "-")
    private fun slugifyClub(name: String): String =
        Normalizer.normalize(name.lowercase(Locale.getDefault()), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .replace("[^a-z0-9]+".toRegex(), "-")
            .trim('-')

    private lateinit var clubsAdapter: ClubsAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var tvUserName: TextView
    private lateinit var etSearch: EditText
    private lateinit var progress: android.view.View
    private lateinit var loadingOverlay: android.view.View

    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var lastLocation: Location? = null
    private val db by lazy { FirebaseFirestore.getInstance() }
    private var fabAddClub: FloatingActionButton? = null
    private var clubsListener: ListenerRegistration? = null
    private var adminListener: ListenerRegistration? = null

    private val locationRequest: LocationRequest by lazy {
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 20_000L)
            .setMinUpdateIntervalMillis(15_000L)
            .setWaitForAccurateLocation(true)
            .setMaxUpdateDelayMillis(25_000L)
            .build()
    }

    private val places = emptyList<Place>()
    private val remotePlaces = mutableListOf<Place>()
    private var currentQuery: String = ""
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            val ok = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (ok) startLocationTracking() else renderWithoutLocation()
        }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            if (isProbablyBad(loc)) return
            lastLocation = loc
            recalcAndRender()
        }
    }

    // Sinhronizuje lokalne klubove u Firestore kolekciju "clubs", izvršava se samo jednom po aktivnosti
    private fun syncClubsToFirestore() {
        if (didSyncClubs) return
        didSyncClubs = true
        for (p in places) {
            val clubId = slugifyClub(p.name)
            val data = mapOf(
                "name" to p.name,
                "lat" to p.lat,
                "lng" to p.lng,
                "createdAt" to Timestamp.now()
            )
            firestore.collection("clubs")
                .document(clubId)
                .set(data, SetOptions.merge())
        }
    }

    // Inicijalizuje UI, bottom navigation, RecyclerView, pretragu, zahteva location dozvole, startuje chat listener i sinhronizuje klubove
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        val bottom = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottom.selectedItemId = R.id.nav_map

        bottom.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_map -> {
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
                    val intent = Intent(this, ProfileActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    startActivity(intent)
                    finish()
                    true
                }
                else -> false
            }
        }

        bottom.setOnItemReselectedListener { item ->
            if (item.itemId == R.id.nav_map) {
                recycler.smoothScrollToPosition(0)
            }
        }

        loadingOverlay = findViewById(R.id.loadingOverlay)
        progress = findViewById(R.id.progress)
        recycler = findViewById(R.id.recyclerClubs)
        recycler.layoutManager = LinearLayoutManager(this)

        val bottomNav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_navigation)
        recycler.clipToPadding = false
        recycler.post {
            val extra = (recycler.resources.displayMetrics.density * 24).toInt()
            recycler.setPadding(
                recycler.paddingLeft,
                recycler.paddingTop,
                recycler.paddingRight,
                recycler.paddingBottom + bottomNav.height + extra
            )
        }

        clubsAdapter = ClubsAdapter(emptyList()) { club ->
            val i = Intent(this, ExploreActivity::class.java)
                .putExtra(ExploreActivity.EXTRA_CLUB_NAME, club.name)
            startActivity(i)
        }
        recycler.adapter = clubsAdapter
        setLoading(true)

        tvUserName = findViewById(R.id.tvUserName)
        tvUserName.text = "${fallbackFirstName()}"

        etSearch = findViewById(R.id.etSearch)
        etSearch.addTextChangedListener {
            currentQuery = it?.toString().orEmpty()
            recalcAndRender()
        }
        etSearch.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                currentQuery = v.text?.toString().orEmpty()
                recalcAndRender()
                true
            } else false
        }

        fabAddClub = findViewById(R.id.fabAdd)
        wireFabForAdmin()

        loadAndSetUserNameFromFirestore()
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
        listenRemoteClubs()

        syncClubsToFirestore()
    }

    // Zaustavlja location tracking i uklanja Firestore listenere da spreči memory leak
    override fun onDestroy() {
        super.onDestroy()
        try { fused.removeLocationUpdates(callback) } catch (_: Exception) {}
        clubsListener?.remove()
        adminListener?.remove()
    }

    // Dohvata trenutnu lokaciju sa timeout-om od 6s i pokreće kontinuirano praćenje lokacije sa location callback-om
    @SuppressLint("MissingPermission")
    private fun startLocationTracking() {
        val cts = com.google.android.gms.tasks.CancellationTokenSource()
        Executors.newSingleThreadScheduledExecutor().schedule({ cts.cancel() }, 6, TimeUnit.SECONDS)
        fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
            .addOnSuccessListener { loc ->
                if (loc != null && !isProbablyBad(loc)) {
                    lastLocation = loc
                    recalcAndRender()
                }
            }
        fused.requestLocationUpdates(locationRequest, callback, mainLooper)
    }

    // Učitava ime korisnika iz Firestore users kolekcije, fallback na displayName ako ime nije dostupno
    private fun loadAndSetUserNameFromFirestore() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        db.collection("users").document(uid).get()
            .addOnSuccessListener { s ->
                val first = s.getString("firstName")?.trim()
                if (!first.isNullOrBlank()) {
                    tvUserName.text = "${first.cap()}"
                } else {
                    FirebaseAuth.getInstance().currentUser?.displayName?.let { dn ->
                        if (!dn.isNullOrBlank()) tvUserName.text = "${firstNameOf(dn)}"
                    }
                }
            }
            .addOnFailureListener { /* ostaje fallback */ }
    }

    // Ekstraktuje prvo ime iz punog imena/email-a i kapitalizuje prvo slovo
    private fun firstNameOf(full: String): String {
        val token = full.trim().split("\\s+".toRegex()).firstOrNull().orEmpty()
        return token.cap()
    }

    // Extension funkcija koja kapitalizuje prvi karakter stringa
    private fun String.cap(): String =
        replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

    // Vraća fallback ime iz displayName ili email-a ako Firestore podaci nisu dostupni
    private fun fallbackFirstName(): String {
        FirebaseAuth.getInstance().currentUser?.displayName?.let {
            if (!it.isNullOrBlank()) return firstNameOf(it)
        }
        FirebaseAuth.getInstance().currentUser?.email?.let { mail ->
            val local = mail.substringBefore('@').replace(".", " ").replace("_", " ").trim()
            if (local.isNotBlank()) return firstNameOf(local)
        }
        return ""
    }

    // Prikazuje klubove sortirane po abecedi bez prikaza udaljenosti kada lokacija nije dostupna
    private fun renderWithoutLocation() {
        val all = mergedPlacesDistinct()
        val filtered = filterPlaces(all, currentQuery)
        val items = filtered
            .sortedBy { it.name.lowercase(Locale.getDefault()) }
            .map { Club(it.name, Int.MAX_VALUE, it.thumbDataUrl) }
        clubsAdapter.submit(items)
        setLoading(false)
    }

    // Preračunava udaljenost svih klubova od trenutne lokacije, filtrira po pretrazi i prikazuje sortirano po udaljenosti
    private fun recalcAndRender() {
        val loc = lastLocation ?: return renderWithoutLocation()
        val all = mergedPlacesDistinct()
        val filtered = filterPlaces(all, currentQuery)
        val items = filtered
            .map { p -> Club(p.name, distanceMeters(loc.latitude, loc.longitude, p.lat, p.lng), p.thumbDataUrl) }
            .sortedBy { it.distanceMeters }
        clubsAdapter.submit(items)
        setLoading(false)
    }

    // Proverava validnost lokacije odbacujući podatke starije od 2 min ili sa skokovima većim od 500km
    private fun isProbablyBad(loc: Location): Boolean {
        val ageMs = SystemClock.elapsedRealtimeNanos() / 1_000_000 - loc.elapsedRealtimeNanos / 1_000_000
        if (ageMs > 2 * 60 * 1000) return true
        lastLocation?.let { prev ->
            val jumpKm = distanceMeters(prev.latitude, prev.longitude, loc.latitude, loc.longitude) / 1000.0
            if (jumpKm > 500) return true
        }
        return false
    }

    // Izračunava razdaljinu u metrima između dve geografske koordinate koristeći Location.distanceBetween()
    private fun distanceMeters(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Int {
        val out = FloatArray(1)
        Location.distanceBetween(aLat, aLng, bLat, bLng, out)
        val m = if (out.isNotEmpty()) out[0].toDouble() else Double.POSITIVE_INFINITY
        if (!m.isFinite()) return Int.MAX_VALUE
        return round(m).toInt()
    }

    // Filtrira klubove po search upitu koristeći normalizovani tekst (bez dijakritika, lowercase)
    private fun filterPlaces(all: List<Place>, query: String): List<Place> {
        val q = normalize(query)
        if (q.isEmpty()) return all
        return all.filter { normalize(it.name).contains(q) }
    }

    // Normalizuje tekst u lowercase i uklanja dijakritike za search poređenje
    private fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.getDefault()), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")

    // Prikazuje/sakriva loading overlay i RecyclerView
    private fun setLoading(loading: Boolean) {
        loadingOverlay.isVisible = loading
        recycler.isVisible = !loading
    }

    // Proverava admin status korisnika i prikazuje FAB dugme za dodavanje kluba samo za admin korisnike
    private fun wireFabForAdmin() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            fabAddClub?.isVisible = false
            return
        }
        adminListener = db.collection("users").document(uid)
            .addSnapshotListener { snap, _ ->
                val isAdmin = snap?.getBoolean("isAdmin") == true
                if (isAdmin) {
                    fabAddClub?.isVisible = true
                } else {
                    db.collection("administrators").document(uid).get()
                        .addOnSuccessListener { doc -> fabAddClub?.isVisible = doc.exists() }
                        .addOnFailureListener { fabAddClub?.isVisible = false }
                }
            }

        fabAddClub?.setOnClickListener {
            startActivity(Intent(this, AddClubActivity::class.java))
        }
    }

    // Postavlja real-time Firestore listener za klubove koji ažurira listu sa thumbnail slikama i poziva recalcAndRender()
    private fun listenRemoteClubs() {
        clubsListener?.remove()
        clubsListener = firestore.collection("clubs")
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    recalcAndRender()
                    return@addSnapshotListener
                }
                remotePlaces.clear()
                snap?.documents?.forEach { d ->
                    val name = d.getString("name") ?: return@forEach
                    val lat = d.getDouble("lat") ?: return@forEach
                    val lng = d.getDouble("lng") ?: return@forEach
                    val thumb = d.getString("thumbDataUrl") // može biti null
                    remotePlaces += Place(name, lat, lng, thumb)
                }
                recalcAndRender()
            }
    }

    // Spaja lokalne i remote klubove uklanjajući duplikate po slug-ified nazivu
    private fun mergedPlacesDistinct(): List<Place> {
        val out = ArrayList<Place>(places.size + remotePlaces.size)
        val seen = HashSet<String>()
        fun addIfNew(p: Place) {
            val key = slugifyClub(p.name)
            if (seen.add(key)) out.add(p)
        }
        places.forEach { addIfNew(it) }
        remotePlaces.forEach { addIfNew(it) }
        return out
    }
}
