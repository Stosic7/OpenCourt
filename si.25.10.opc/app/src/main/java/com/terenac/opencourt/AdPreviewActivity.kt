package com.terenac.opencourt

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

class AdPreviewActivity : AppCompatActivity() {

    private lateinit var tvAdName: TextView
    private lateinit var tvAdCategory: TextView
    private lateinit var tvAdDescription: TextView
    private lateinit var tvAdAuthor: TextView
    private lateinit var tvAdContact: TextView
    private lateinit var tvAdPrice: TextView
    private lateinit var priceSection: View
    private lateinit var contactCard: View
    private lateinit var etComment: EditText
    private lateinit var btnSendComment: MaterialButton
    private lateinit var btnShowContact: View
    private lateinit var rvComments: RecyclerView
    private lateinit var tvNoComments: TextView
    private lateinit var tvCommentCount: TextView
    private lateinit var loadingOverlay: View
    private lateinit var btnBack: MaterialButton
    private lateinit var commentInputSection: View

    private lateinit var commentsAdapter: CommentsAdapter
    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private val auth by lazy { FirebaseAuth.getInstance() }
    private var listenerRegistration: ListenerRegistration? = null

    private val adId: String by lazy { intent.getStringExtra("AD_ID") ?: "" }

    // Inicijalizuje UI komponente, učitava podatke oglasa, postavlja listener za komentare i sakriva komentare ako je oglas admin-pinned
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ad_preview)

        // Pronalazi i inicijalizuje sve UI komponente i postavlja RecyclerView adapter za komentare
        initViews()
        // Postavlja click listenere za dugmad: nazad, prikaz kontakta i slanje komentara
        wireClicks()
        // Učitava podatke oglasa iz Intent extras-a i popunjava UI elemente, prilagođava prikaz za admin oglase
        loadAdData()
        // Postavlja real-time Firestore listener za komentare, sortira ih po timestamp-u i ažurira adapter
        setupComments()

        val pinnedByAdmin = intent.getBooleanExtra("PINNED_BY_ADMIN", false)
        val until = intent.getLongExtra("PINNED_UNTIL", 0L)
        if (pinnedByAdmin && until > System.currentTimeMillis()) {
            commentInputSection.isVisible = false
            tvNoComments.isVisible = false
        }
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btnBack)

        tvAdName = findViewById(R.id.tvAdName)
        tvAdCategory = findViewById(R.id.tvAdCategory)
        tvAdDescription = findViewById(R.id.tvAdDescription)
        tvAdAuthor = findViewById(R.id.tvAdAuthor)
        tvAdContact = findViewById(R.id.tvAdContact)
        tvAdPrice = findViewById(R.id.tvAdPrice)
        priceSection = findViewById(R.id.priceSection)
        contactCard = findViewById(R.id.contactCard)

        rvComments = findViewById(R.id.rvComments)
        tvNoComments = findViewById(R.id.tvNoComments)
        tvCommentCount = findViewById(R.id.tvCommentCount)

        etComment = findViewById(R.id.etComment)
        btnSendComment = findViewById(R.id.btnSendComment)
        btnShowContact = findViewById(R.id.btnShowContact)

        loadingOverlay = findViewById(R.id.loadingOverlay)
        commentInputSection = findViewById(R.id.commentInputSection)

        commentsAdapter = CommentsAdapter()
        rvComments.layoutManager = LinearLayoutManager(this)
        rvComments.adapter = commentsAdapter
    }

    private fun wireClicks() {
        btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        btnShowContact.setOnClickListener { contactCard.isVisible = !contactCard.isVisible }
        btnSendComment.setOnClickListener { sendComment() }
    }

    private fun loadAdData() {
        val title = intent.getStringExtra("AD_TITLE") ?: ""
        val userName = intent.getStringExtra("AD_USER_NAME") ?: ""
        val description = intent.getStringExtra("AD_DESCRIPTION") ?: ""
        val category = intent.getStringExtra("AD_CATEGORY") ?: ""
        val price = intent.getStringExtra("AD_PRICE") ?: ""
        val contact = intent.getStringExtra("AD_CONTACT") ?: ""
        val adminClub = intent.getStringExtra("ADMIN_CLUB_NAME") ?: ""

        tvAdName.text = title
        tvAdCategory.text = category
        tvAdDescription.text = description

        val pinnedByAdmin = intent.getBooleanExtra("PINNED_BY_ADMIN", false)
        val until = intent.getLongExtra("PINNED_UNTIL", 0L)
        tvAdAuthor.text = if (pinnedByAdmin && until > System.currentTimeMillis() && adminClub.isNotBlank())
            "Administrator — $adminClub" else userName

        if (price.isNotBlank()) {
            tvAdPrice.text = price
            priceSection.isVisible = true
        } else {
            priceSection.isVisible = false
        }

        tvAdContact.text = if (contact.isNotBlank()) contact else "Nije ostavljen kontakt."
    }

    private fun setupComments() {
        listenerRegistration = firestore.collection("advertisements")
            .document(adId)
            .collection("comments")
            .orderBy("timestamp")
            .addSnapshotListener { snap, _ ->
                val list = snap?.documents?.map { d ->
                    Comment(
                        id = d.id,
                        userName = d.getString("userName") ?: "Korisnik",
                        text = d.getString("text") ?: "",
                        timestamp = d.getLong("timestamp") ?: 0L
                    )
                }.orEmpty()
                commentsAdapter.submitList(list)
                tvNoComments.isVisible = list.isEmpty()
                tvCommentCount.text = "Komentari (${list.size})"
            }
    }

    // Validira unos komentara, proverava autentifikaciju i blokira komentarisanje za admin-pinned oglase
    private fun sendComment() {
        val commentText = etComment.text?.toString()?.trim()
        if (commentText.isNullOrBlank()) {
            Toast.makeText(this, "Unesite komentar", Toast.LENGTH_SHORT).show()
            return
        }
        if (commentText.length < 2) {
            Toast.makeText(this, "Komentar je prekratak", Toast.LENGTH_SHORT).show()
            return
        }

        val currentUser = FirebaseAuth.getInstance().currentUser ?: run {
            Toast.makeText(this, "Morate biti prijavljeni.", Toast.LENGTH_SHORT).show()
            return
        }

        // Bezbednosna blokada – ako je ad pinovan, ne dozvoli slanje
        val pinned = intent.getBooleanExtra("PINNED_BY_ADMIN", false) &&
                intent.getLongExtra("PINNED_UNTIL", 0L) > System.currentTimeMillis()
        if (pinned) {
            Toast.makeText(this, "Komentarisanje je isključeno za ovaj oglas.", Toast.LENGTH_SHORT).show()
            return
        }

        setLoading(true)

        firestore.collection("users").document(currentUser.uid).get()
            .addOnSuccessListener { doc ->
                val firstName = doc.getString("firstName") ?: ""
                val lastName = doc.getString("lastName") ?: ""
                val userName = listOf(firstName, lastName)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .ifBlank {
                        currentUser.displayName ?: (currentUser.email?.substringBefore('@') ?: "Korisnik")
                    }
                actuallySendComment(adId, currentUser.uid, userName, commentText)
            }
            .addOnFailureListener {
                val userName = currentUser.displayName ?: (currentUser.email?.substringBefore('@') ?: "Korisnik")
                actuallySendComment(adId, currentUser.uid, userName, commentText)
            }
    }

    // Šalje komentar u Firestore, dodaje 2 poena korisniku i scroll-uje RecyclerView na novi komentar
    private fun actuallySendComment(adId: String, userId: String, userName: String, commentText: String) {
        val comment = hashMapOf(
            "userId" to userId,
            "userName" to userName,
            "text" to commentText,
            "timestamp" to System.currentTimeMillis()
        )

        firestore.collection("advertisements")
            .document(adId)
            .collection("comments")
            .add(comment)
            .addOnSuccessListener {
                firestore.collection("users").document(userId)
                    .update(
                        mapOf(
                            "points" to FieldValue.increment(2L),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    .addOnCompleteListener {
                        setLoading(false)
                        btnSendComment.isEnabled = true
                        etComment.text?.clear()
                        Toast.makeText(this, "Komentar poslat", Toast.LENGTH_SHORT).show()
                        rvComments.post {
                            rvComments.smoothScrollToPosition(commentsAdapter.itemCount - 1)
                        }
                    }
            }
            .addOnFailureListener { e ->
                setLoading(false)
                btnSendComment.isEnabled = true
                Toast.makeText(this, "Greška: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    // Prikazuje ili sakriva loading overlay
    private fun setLoading(loading: Boolean) {
        loadingOverlay.isVisible = loading
    }

    // Uklanja Firestore listener pri uništenju aktivnosti da spreči memory leak
    override fun onDestroy() {
        super.onDestroy()
        listenerRegistration?.remove()
    }
}
