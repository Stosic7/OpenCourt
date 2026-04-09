package com.terenac.opencourt

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import java.text.Normalizer
import java.util.Locale

data class Advertisement(
    val id: String = "",
    val userId: String = "",
    val userName: String = "",
    val title: String = "",
    val description: String = "",
    val category: String = "",
    val price: String = "",
    val contact: String = "",
    val timestamp: Long = 0L,
    val pinnedByAdmin: Boolean = false,
    val pinnedUntil: Long = 0L,
    val adminClubId: String = "",
    val adminClubName: String = ""
)

class AdvertisementActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AdAdapter
    private lateinit var loadingOverlay: View
    private lateinit var etSearch: EditText
    private lateinit var btnCreateAd: TextView
    private lateinit var chipAll: LinearLayout
    private lateinit var chipSale: LinearLayout
    private lateinit var chipDiscussion: LinearLayout
    private lateinit var chipOther: LinearLayout

    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private var listenerRegistration: ListenerRegistration? = null
    private val allAds = mutableListOf<Advertisement>()
    private var currentFilter: String? = null
    private var searchQuery: String = ""
    private var scrollPosition: Int = 0
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val REQUEST_CREATE_AD = 1001
    }

    // Inicijalizuje UI, bottom navigation, filtere, pretragu, RecyclerView i učitava oglase iz Firestore-a
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advertisement)

        // Pronalazi i inicijalizuje sve UI komponente i postavlja listener za back dugme
        initViews()
        // Postavlja bottom navigation sa navigacijom između ekrana i smooth scroll na reselect
        setupBottomNavigation()
        // Postavlja click listenere za filter chip-ove i inicijalizuje "All" kao podrazumevani filter
        setupFilters()
        // Dodaje TextWatcher na search polje koji poziva applyFilters() nakon svake promene teksta
        setupSearch()
        // Inicijalizuje RecyclerView sa adapterom, podešava padding za bottom navigation i scroll listener za čuvanje pozicije
        setupRecyclerView()
        // Postavlja listener na dugme za kreiranje oglasa koji otvara CreateAdActivity
        setupCreateButton()
        // Postavlja real-time Firestore listener koji učitava oglase sortirane po timestamp-u i poziva applyFilters()
        loadAdvertisements()
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.advertismentList)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        etSearch = findViewById(R.id.etSearchAds)
        btnCreateAd = findViewById(R.id.btnCreateAd)
        chipAll = findViewById(R.id.chipAll)
        chipSale = findViewById(R.id.chipSale)
        chipDiscussion = findViewById(R.id.chipDiscussion)
        chipOther = findViewById(R.id.chipOther)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            goHome()
        }
    }

    private fun setupBottomNavigation() {
        val bottom = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottom.selectedItemId = R.id.nav_sports

        bottom.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_map -> {
                    saveScrollPosition()
                    goHome()
                    true
                }
                R.id.nav_sports -> {
                    true
                }
                R.id.nav_profile -> {
                    saveScrollPosition()
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
            if (item.itemId == R.id.nav_sports) {
                recyclerView.smoothScrollToPosition(0)
            }
        }
    }

    // Proverava da li je oglas trenutno admin-pinned poređenjem pinnedUntil vremena sa trenutnim vremenom
    private fun isActivePin(ad: Advertisement, nowMs: Long = System.currentTimeMillis()): Boolean {
        val until = if (ad.pinnedUntil in 1_000_000_000L..9_999_999_999L) ad.pinnedUntil * 1000 else ad.pinnedUntil
        return ad.pinnedByAdmin && until > nowMs
    }

    private fun setupFilters() {
        selectFilter(all = true)

        chipAll.setOnClickListener {
            selectFilter(all = true)
            currentFilter = null
            applyFilters()
        }

        chipSale.setOnClickListener {
            selectFilter(sale = true)
            currentFilter = "Prodaja"
            applyFilters()
        }

        chipDiscussion.setOnClickListener {
            selectFilter(discussion = true)
            currentFilter = "Diskusija"
            applyFilters()
        }

        chipOther.setOnClickListener {
            selectFilter(other = true)
            currentFilter = "Ostalo"
            applyFilters()
        }
    }

    private fun selectFilter(
        all: Boolean = false,
        sale: Boolean = false,
        discussion: Boolean = false,
        other: Boolean = false
    ) {
        chipAll.setBackgroundResource(
            if (all) R.drawable.tab_chip_selected else R.drawable.tab_chip
        )
        chipSale.setBackgroundResource(
            if (sale) R.drawable.tab_chip_selected else R.drawable.tab_chip
        )
        chipDiscussion.setBackgroundResource(
            if (discussion) R.drawable.tab_chip_selected else R.drawable.tab_chip
        )
        chipOther.setBackgroundResource(
            if (other) R.drawable.tab_chip_selected else R.drawable.tab_chip
        )
    }

    private fun setupSearch() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.trim() ?: ""
                applyFilters()
            }
        })
    }

    private fun applyFilters() {
        val now = System.currentTimeMillis()

        var filtered = allAds.toMutableList()

        currentFilter?.let { wanted ->
            val norm = normalizeText(wanted)
            filtered = filtered.filter { normalizeText(it.category) == norm }.toMutableList()
        }

        if (searchQuery.isNotEmpty()) {
            val q = normalizeText(searchQuery)
            filtered = filtered.filter { ad ->
                normalizeText(ad.title).contains(q) ||
                        normalizeText(ad.description).contains(q) ||
                        normalizeText(ad.category).contains(q)
            }.toMutableList()
        }

        val activePins = allAds.filter { isActivePin(it, now) }
        if (activePins.isNotEmpty()) {
            val seen = filtered.mapTo(mutableSetOf()) { it.id }
            activePins.forEach { if (seen.add(it.id)) filtered.add(0, it) }
        }

        val pinned = filtered
            .filter { isActivePin(it, now) }
            .sortedByDescending {
                val until = if (it.pinnedUntil in 1_000_000_000L..9_999_999_999L) it.pinnedUntil * 1000 else it.pinnedUntil
                until
            }

        val regular = filtered
            .filterNot { isActivePin(it, now) }
            .sortedByDescending { it.timestamp }

        val finalList = pinned + regular
        adapter.submitList(finalList)

        if (scrollPosition > 0 && finalList.isNotEmpty()) {
            handler.postDelayed({
                recyclerView.scrollToPosition(scrollPosition.coerceAtMost(finalList.size - 1))
            }, 100)
        }
    }


    private fun setupRecyclerView() {
        adapter = AdAdapter { ad -> openAdPreview(ad) }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        recyclerView.setHasFixedSize(true)

        val bottomNav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_navigation)
        recyclerView.clipToPadding = false
        recyclerView.post {
            val extra = (recyclerView.resources.displayMetrics.density * 24).toInt()
            recyclerView.setPadding(
                recyclerView.paddingLeft,
                recyclerView.paddingTop,
                recyclerView.paddingRight,
                recyclerView.paddingBottom + bottomNav.height + extra
            )
        }

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val lm = recyclerView.layoutManager as? LinearLayoutManager
                scrollPosition = lm?.findFirstVisibleItemPosition() ?: 0
            }
        })
    }

    private fun setupCreateButton() {
        btnCreateAd.setOnClickListener {
            val intent = Intent(this, CreateAdActivity::class.java)
            startActivityForResult(intent, REQUEST_CREATE_AD)
        }
    }

    private fun loadAdvertisements() {
        setLoading(true)

        listenerRegistration = firestore.collection("advertisements")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                setLoading(false)

                if (error != null) {
                    return@addSnapshotListener
                }

                if (snapshot != null && !snapshot.isEmpty) {
                    val newAds = snapshot.documents.mapNotNull { doc ->
                        try {
                            Advertisement(
                                id = doc.id,
                                userId = doc.getString("userId") ?: "",
                                userName = doc.getString("userName") ?: "",
                                title = doc.getString("title") ?: "",
                                description = doc.getString("description") ?: "",
                                category = doc.getString("category") ?: "",
                                price = (doc.get("price") ?: "").toString(),
                                contact = doc.getString("contact") ?: "",
                                timestamp = doc.getLong("timestamp") ?: 0L,
                                pinnedByAdmin = doc.getBoolean("pinnedByAdmin") == true,
                                pinnedUntil = doc.getLong("pinnedUntil") ?: 0L,
                                adminClubId = doc.getString("adminClubId") ?: "",
                                adminClubName = doc.getString("adminClubName") ?: ""
                            )
                        } catch (_: Exception) { null }
                    }

                    allAds.clear()
                    allAds.addAll(newAds)
                    applyFilters()
                } else {
                    allAds.clear()
                    adapter.submitList(emptyList())
                }
            }
    }

    // Otvara AdPreviewActivity i prosleđuje sve podatke oglasa kroz Intent extras
    private fun openAdPreview(ad: Advertisement) {
        val intent = Intent(this, AdPreviewActivity::class.java).apply {
            putExtra("AD_ID", ad.id)
            putExtra("AD_USER_ID", ad.userId)
            putExtra("AD_USER_NAME", ad.userName)
            putExtra("AD_TITLE", ad.title)
            putExtra("AD_DESCRIPTION", ad.description)
            putExtra("AD_CATEGORY", ad.category)
            putExtra("AD_PRICE", ad.price)
            putExtra("AD_CONTACT", ad.contact)
            putExtra("AD_TIMESTAMP", ad.timestamp)
            putExtra("PINNED_BY_ADMIN", ad.pinnedByAdmin)
            putExtra("PINNED_UNTIL", ad.pinnedUntil)
            putExtra("ADMIN_CLUB_NAME", ad.adminClubName)
        }
        startActivity(intent)
    }

    // Čuva trenutnu scroll poziciju prvog vidljivog item-a u RecyclerView-u
    private fun saveScrollPosition() {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
        scrollPosition = layoutManager?.findFirstVisibleItemPosition() ?: 0
    }

    // Vraća scroll poziciju kada se aktivnost vrati u fokus
    override fun onResume() {
        super.onResume()
        if (scrollPosition > 0 && allAds.isNotEmpty()) {
            handler.postDelayed({
                recyclerView.scrollToPosition(scrollPosition.coerceAtMost(allAds.size - 1))
            }, 100)
        }
    }

    // Scroll-uje na vrh liste kada se korisnik vrati nakon kreiranja oglasa
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CREATE_AD && resultCode == RESULT_OK) {
            recyclerView.smoothScrollToPosition(0)
        }
    }

    // Prikazuje ili sakriva loading overlay
    private fun setLoading(loading: Boolean) {
        loadingOverlay.isVisible = loading
    }

    // Navigira na HomeActivity sa CLEAR_TOP i SINGLE_TOP flagovima
    private fun goHome() {
        val intent = Intent(this, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
        finish()
    }

    // Normalizuje tekst u lowercase i uklanja dijakritike za poređenje nezavisno od velikih slova
    private fun normalizeText(text: String): String {
        return Normalizer.normalize(text.lowercase(Locale.getDefault()), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
    }

    // Uklanja Firestore listener i sve handler callback-ove da spreči memory leak
    override fun onDestroy() {
        super.onDestroy()
        listenerRegistration?.remove()
        handler.removeCallbacksAndMessages(null)
    }
}
