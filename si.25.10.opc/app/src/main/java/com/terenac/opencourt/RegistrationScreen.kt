package com.terenac.opencourt

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.userProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

class RegistrationScreen : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private val db by lazy { FirebaseFirestore.getInstance() }

    private lateinit var etIme: EditText
    private lateinit var etPrezime: EditText
    private lateinit var etTelefon: EditText
    private lateinit var etEmail: EditText
    private lateinit var etSifra: EditText
    private lateinit var etPonoviSifru: EditText
    private lateinit var btnRegistrujSe: Button
    private lateinit var cbClubAdmin: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.registration_screen)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        auth = FirebaseAuth.getInstance()

        etIme = findViewById(R.id.etIme)
        etPrezime = findViewById(R.id.etPrezime)
        etTelefon = findViewById(R.id.etTelefon)
        etEmail = findViewById(R.id.etEmail)
        etSifra = findViewById(R.id.etSifra)
        etPonoviSifru = findViewById(R.id.etPonoviSifru)
        btnRegistrujSe = findViewById(R.id.btnRegistrujSe)
        cbClubAdmin = findViewById(R.id.cbClubAdmin)

        btnRegistrujSe.setOnClickListener {
            val ime = etIme.text.toString().trim()
            val prezime = etPrezime.text.toString().trim()
            val telefon = etTelefon.text.toString().trim()
            val email = etEmail.text.toString().trim()
            val sifra = etSifra.text.toString()
            val ponoviSifru = etPonoviSifru.text.toString()
            val wantsAdmin = cbClubAdmin.isChecked

            if (ime.isBlank()) {
                Toast.makeText(this, "Unesite ime", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }
            if (prezime.isBlank()) {
                Toast.makeText(this, "Unesite prezime", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }
            if (telefon.isBlank()) {
                Toast.makeText(this, "Unesite broj telefona", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }
            if (email.isBlank()) {
                Toast.makeText(this, "Unesite email", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }
            if (sifra.isBlank()) {
                Toast.makeText(this, "Unesite šifru", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }
            if (sifra.length < 6) {
                Toast.makeText(this, "Šifra mora imati najmanje 6 karaktera", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }
            if (sifra != ponoviSifru) {
                Toast.makeText(this, "Šifre se ne poklapaju", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }

            auth.createUserWithEmailAndPassword(email, sifra)
                .addOnCompleteListener(this) { task ->
                    if (!task.isSuccessful) {
                        val msg = task.exception?.localizedMessage ?: "Registracija neuspešna"
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                        return@addOnCompleteListener
                    }

                    val user = auth.currentUser
                    val profile = userProfileChangeRequest { displayName = "$ime $prezime" }
                    user?.updateProfile(profile)

                    user?.uid?.let { uid ->
                        val now = FieldValue.serverTimestamp()
                        val userData = hashMapOf(
                            "uid" to uid,
                            "firstName" to ime,
                            "lastName" to prezime,
                            "phone" to telefon,
                            "email" to email,
                            "createdAt" to now,
                            "updatedAt" to now,
                            "isAdmin" to wantsAdmin
                        )
                        db.collection("users").document(uid).set(userData)

                        if (wantsAdmin) {
                            val adminData = hashMapOf(
                                "uid" to uid,
                                "firstName" to ime,
                                "lastName" to prezime,
                                "phone" to telefon,
                                "email" to email,
                                "createdAt" to now
                            )
                            db.collection("administrators").document(uid).set(adminData)
                        }
                    }

                    Toast.makeText(this, "Uspešna registracija", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this, FirstTimeNotificationActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    })
                    finish()
                }
        }
    }
}
