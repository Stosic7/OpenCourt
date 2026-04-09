package com.terenac.opencourt

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.auth.FirebaseAuth

class StartingScreenActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var loginButton: Button
    private lateinit var registerText: TextView
    private lateinit var forgotPasswordText: TextView

    // Inicijalizuje UI komponente, proverava autentifikaciju i postavlja listenere za login, registraciju i forgot password
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_starting_screen)


        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        auth = FirebaseAuth.getInstance()
        emailInput = findViewById(R.id.emailInput)
        passwordInput = findViewById(R.id.passwordInput)
        loginButton = findViewById(R.id.loginButton)
        registerText = findViewById(R.id.register)
        forgotPasswordText = findViewById(R.id.forgotPassword)

        // Postavlja listener za login dugme
        loginButton.setOnClickListener {
            val email = emailInput.text.toString().trim()
            val password = passwordInput.text.toString()

            if (email.isBlank()) {
                Toast.makeText(this, "Unesite email", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (password.isBlank()) {
                Toast.makeText(this, "Unesite šifru", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            auth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this) { task ->
                    if (task.isSuccessful) {
                        val user = auth.currentUser
                        if (user != null && !Prefs.isOnboardingSeen(this, user.uid)) {
                            startActivity(Intent(this, FirstTimeNotificationActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            })
                        } else {
                            startActivity(Intent(this, HomeActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            })
                        }
                        finish()
                    } else {
                        val msg = task.exception?.localizedMessage ?: "Prijava neuspešna"
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    }
                }
        }

        // Postavlja listener za registraciju
        registerText.setOnClickListener {
            startActivity(Intent(this, RegistrationScreen::class.java))
        }

        // Postavlja listener za forgot password
        forgotPasswordText.setOnClickListener {
            showForgotPasswordDialog()
        }
    }

    // Proverava da li je korisnik već prijavljen pri startu aktivnosti i redirektuje na odgovarajući ekran
    override fun onStart() {
        super.onStart()
        if (this::auth.isInitialized) {
            auth.currentUser?.let { user ->
                if (!Prefs.isOnboardingSeen(this, user.uid)) {
                    startActivity(Intent(this, FirstTimeNotificationActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    })
                } else {
                    startActivity(Intent(this, HomeActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    })
                }
                finish()
            }
        }
    }

    // Prikazuje dijalog za unos email adrese i šalje zahtev za reset šifre preko Firebase Authentication
    private fun showForgotPasswordDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(12))
            background = makeDialogBackground()
        }

        val input = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            hint = "Unesite email adresu"
            setText(emailInput.text.toString().trim())
            styleEditText(this)
        }
        container.addView(input)

        val dialog = AlertDialog.Builder(this)
            .setCustomTitle(makeThemedTitle("Resetovanje šifre"))
            .setView(container)
            .setPositiveButton("Pošalji", null)
            .setNegativeButton("Otkaži", null)
            .create()

        dialog.setOnShowListener {
            styleDialogButtons(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val email = input.text.toString().trim()
                if (email.isBlank()) {
                    Toast.makeText(this, "Unesite email adresu", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                auth.sendPasswordResetEmail(email)
                    .addOnSuccessListener {
                        Toast.makeText(
                            this,
                            "Link za resetovanje šifre je poslat na $email",
                            Toast.LENGTH_LONG
                        ).show()
                        dialog.dismiss()
                    }
                    .addOnFailureListener { e ->
                        val errorMsg = when {
                            e.message?.contains("no user record", true) == true ->
                                "Ne postoji korisnik sa ovom email adresom"
                            e.message?.contains("invalid-email", true) == true ->
                                "Neispravna email adresa"
                            else -> "Greška: ${e.localizedMessage}"
                        }
                        Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
                    }
            }
        }
        dialog.window?.setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
        dialog.show()
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
}
