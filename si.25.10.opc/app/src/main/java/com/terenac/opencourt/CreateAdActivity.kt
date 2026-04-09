package com.terenac.opencourt

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import com.google.android.material.button.MaterialButton
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

class CreateAdActivity : AppCompatActivity() {

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }
    private lateinit var btnBack: MaterialButton
    private lateinit var btnPublish: AppCompatButton
    private lateinit var etAdName: EditText
    private lateinit var etAdDescription: EditText
    private lateinit var spinnerCategory: Spinner
    private lateinit var etAdPrice: EditText
    private lateinit var etAdContact: EditText
    private lateinit var loadingOverlay: View
    private lateinit var adminPanel: View
    private lateinit var spinnerAdminClub: Spinner
    private lateinit var spinnerPinDays: Spinner
    private val adminClubs = mutableListOf<Pair<String,String>>() // (id, name)

    // Inicijalizuje UI komponente, postavlja adaptere za spinner-e kategorija i pin trajanja, i proverava admin status
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_create_ad)

        btnBack = findViewById(R.id.btnBack)
        btnPublish = findViewById(R.id.btnPublish)
        etAdName = findViewById(R.id.etAdName)
        etAdDescription = findViewById(R.id.etAdDescription)
        spinnerCategory = findViewById(R.id.spinnerCategory)
        etAdPrice = findViewById(R.id.etAdPrice)
        etAdContact = findViewById(R.id.etAdContact)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        adminPanel = findViewById(R.id.adminPanel)
        spinnerAdminClub = findViewById(R.id.spinnerAdminClub)
        spinnerPinDays = findViewById(R.id.spinnerPinDays)
        btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val categories = listOf("Prodaja", "Diskusija", "Ostalo")
        spinnerCategory.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            categories
        )

        spinnerPinDays.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            (1..7).map { "$it dan(a)" }
        )

        wireAdminPanel()
        btnPublish.setOnClickListener { publishAd() }
    }

    // Proverava da li je korisnik admin, učitava klubove koje poseduje i prikazuje admin panel ako postoje klubovi
    private fun wireAdminPanel() {
        val uid = auth.currentUser?.uid ?: return
        adminPanel.visibility = View.GONE

        db.collection("users").document(uid).get()
            .addOnSuccessListener { userDoc ->
                val isAdmin = userDoc.getBoolean("isAdmin") == true
                if (!isAdmin) return@addOnSuccessListener

                db.collection("clubs")
                    .whereEqualTo("ownerAdminId", uid)
                    .get()
                    .addOnSuccessListener { qs ->
                        adminClubs.clear()
                        adminClubs += qs.documents.map { it.id to (it.getString("name") ?: it.id) }
                        if (adminClubs.isNotEmpty()) {
                            adminPanel.visibility = View.VISIBLE
                            spinnerAdminClub.adapter = ArrayAdapter(
                                this,
                                android.R.layout.simple_spinner_dropdown_item,
                                adminClubs.map { it.second }
                            )
                        }
                    }
            }
    }

    // Validira unos, kreira oglas sa osnovnim podacima ili admin-pinned oglasom sa klubom i trajanjem, čuva u Firestore
    private fun publishAd() {
        val name = etAdName.text.toString().trim()
        val desc = etAdDescription.text.toString().trim()
        val category = spinnerCategory.selectedItem?.toString()?.trim().orEmpty()
        val contact = etAdContact.text.toString().trim()
        val priceText = etAdPrice.text.toString().trim()

        if (name.isEmpty()) { toast("Unesite naziv oglasa"); return }
        if (desc.isEmpty()) { toast("Unesite opis oglasa"); return }
        if (category.isEmpty()) { toast("Izaberite kategoriju"); return }
        if (contact.isEmpty()) { toast("Unesite kontakt"); return }

        val uid = auth.currentUser?.uid ?: run { toast("Niste prijavljeni"); return }
        setLoading(true)

        val authorName = auth.currentUser?.displayName
            ?: auth.currentUser?.email?.substringBefore('@')
            ?: "Korisnik"

        val base = hashMapOf(
            "title" to name,
            "description" to desc,
            "category" to category,
            "contact" to contact,
            "price" to priceText,
            "userId" to uid,
            "userName" to authorName,
            "timestamp" to System.currentTimeMillis()
        )

        val isAdminAd = adminPanel.visibility == View.VISIBLE
        if (isAdminAd) {
            if (adminClubs.isEmpty()) {
                setLoading(false); toast("Nemate klubove za admin oglas."); return
            }
            val clubIndex = spinnerAdminClub.selectedItemPosition
            if (clubIndex !in adminClubs.indices) {
                setLoading(false); toast("Izaberite klub."); return
            }
            val (clubId, clubName) = adminClubs[clubIndex]
            val days = (spinnerPinDays.selectedItemPosition + 1).coerceIn(1, 7)
            val until = System.currentTimeMillis() + days * 24L * 60 * 60 * 1000

            base["pinnedByAdmin"] = true
            base["pinnedUntil"] = until
            base["adminClubId"] = clubId
            base["adminClubName"] = clubName
        } else {
            base["pinnedByAdmin"] = false
            base["pinnedUntil"] = 0L
            base["adminClubId"] = ""
            base["adminClubName"] = ""
        }

        FirebaseFirestore.getInstance().collection("advertisements")
            .add(base)
            .addOnSuccessListener {
                setLoading(false)
                setResult(RESULT_OK)
                toast(if (isAdminAd) "Admin oglas objavljen" else "Oglas objavljen")
                finish()
            }
            .addOnFailureListener { e ->
                setLoading(false)
                toast(e.localizedMessage ?: "Greška pri objavi")
            }
    }

    // Prikazuje ili sakriva loading overlay i omogućava/onemogućava dugme za objavljivanje
    private fun setLoading(on: Boolean) {
        loadingOverlay.visibility = if (on) View.VISIBLE else View.GONE
        btnPublish.isEnabled = !on
    }

    // Helper metod za prikazivanje Toast poruka
    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
