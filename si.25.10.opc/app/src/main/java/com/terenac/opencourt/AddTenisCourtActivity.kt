package com.terenac.opencourt

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

class AddTenisCourtActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CLUB_ID = "extra_club_id"
    }

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }
    private lateinit var btnBack: MaterialButton
    private lateinit var btnSave: androidx.appcompat.widget.AppCompatButton
    private lateinit var etCourtName: EditText
    private lateinit var cardClay: MaterialCardView
    private lateinit var cardGrass: MaterialCardView
    private lateinit var cardHard: MaterialCardView
    private lateinit var loadingOverlay: View

    private var selectedSurface: String? = null
    private val highlight = Color.parseColor("#FFD54F")

    private val clubId: String by lazy { intent.getStringExtra(EXTRA_CLUB_ID) ?: "" }

    // Inicijalizuje UI komponente, proverava da li postoji clubId i verifikuje vlasništvo nad klubom
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_tenis_court)

        if (clubId.isBlank()) {
            Toast.makeText(this, "Nedostaje clubId.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        btnBack = findViewById(R.id.btnBack)
        btnSave = findViewById(R.id.btnSave)
        etCourtName = findViewById(R.id.etCourtName)
        cardClay = findViewById(R.id.cardClay)
        cardGrass = findViewById(R.id.cardGrass)
        cardHard = findViewById(R.id.cardHard)
        loadingOverlay = findViewById(R.id.loadingOverlay)

        btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        wireSurfacePickers()

        btnSave.setOnClickListener { saveCourt() }

        enforceOwnerOrFinish()
    }

    // Postavlja listenere za kartice površina terena i implementira vizuelnu selekciju sa highlight-om
    private fun wireSurfacePickers() {
        fun setSelected(card: MaterialCardView?) {
            listOf(cardClay, cardGrass, cardHard).forEach {
                it.strokeColor = Color.parseColor("#33FFFFFF")
                it.strokeWidth = dp(2)
            }
            card?.let {
                it.strokeColor = highlight
                it.strokeWidth = dp(3)
            }
        }

        cardClay.setOnClickListener { selectedSurface = "clay"; setSelected(cardClay) }
        cardGrass.setOnClickListener { selectedSurface = "grass"; setSelected(cardGrass) }
        cardHard.setOnClickListener { selectedSurface = "hard"; setSelected(cardHard) }
    }

    // Proverava da li je trenutni korisnik vlasnik kluba, u suprotnom zatvara aktivnost
    private fun enforceOwnerOrFinish() {
        val uid = auth.currentUser?.uid ?: run {
            Toast.makeText(this, "Niste prijavljeni.", Toast.LENGTH_LONG).show()
            finish(); return
        }

        setLoading(true)
        db.collection("clubs").document(clubId).get()
            .addOnSuccessListener { doc ->
                setLoading(false)
                val owner = doc.getString("ownerAdminId")
                if (owner != uid) {
                    Toast.makeText(this, "Nemate dozvolu za ovaj klub.", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
            .addOnFailureListener {
                setLoading(false)
                Toast.makeText(this, "Greška pri čitanju kluba.", Toast.LENGTH_LONG).show()
                finish()
            }
    }

    // Validira unos, ponovo proverava vlasništvo i čuva novi teren u Firestore podkolekciju courts
    private fun saveCourt() {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            Toast.makeText(this, "Niste prijavljeni.", Toast.LENGTH_SHORT).show()
            return
        }

        val name = etCourtName.text.toString().trim()
        if (name.isEmpty()) {
            etCourtName.error = "Unesite naziv terena"
            return
        }
        val surface = selectedSurface ?: run {
            Toast.makeText(this, "Izaberite tip podloge.", Toast.LENGTH_SHORT).show()
            return
        }

        setLoading(true)

        db.collection("clubs").document(clubId).get()
            .addOnSuccessListener { doc ->
                val owner = doc.getString("ownerAdminId")
                if (owner != uid) {
                    setLoading(false)
                    Toast.makeText(this, "Nemate dozvolu za ovaj klub.", Toast.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }

                val now = FieldValue.serverTimestamp()
                val courtData = hashMapOf(
                    "name" to name,
                    "surface" to surface,
                    "createdAt" to now,
                    "updatedAt" to now,
                    "clubId" to clubId
                )

                db.collection("clubs").document(clubId)
                    .collection("courts")
                    .add(courtData)
                    .addOnSuccessListener {
                        setLoading(false)
                        Toast.makeText(this, "Teren sačuvan.", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    .addOnFailureListener { e ->
                        setLoading(false)
                        Toast.makeText(this, e.localizedMessage ?: "Greška pri čuvanju.", Toast.LENGTH_LONG).show()
                    }
            }
            .addOnFailureListener { e ->
                setLoading(false)
                Toast.makeText(this, e.localizedMessage ?: "Greška pri proveri kluba.", Toast.LENGTH_LONG).show()
            }
    }

    // Prikazuje ili sakriva loading overlay i omogućava/onemogućava dugme za čuvanje
    private fun setLoading(on: Boolean) {
        loadingOverlay.visibility = if (on) View.VISIBLE else View.GONE
        btnSave.isEnabled = !on
    }

    // Konvertuje dp vrednost u piksele na osnovu gustine ekrana
    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()
}
