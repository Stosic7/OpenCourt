package com.terenac.opencourt

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.RelativeLayout
import android.widget.Spinner
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

class EquipmentActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RACKETS = "extra_rackets"
        const val EXTRA_BALLS = "extra_balls"
    }

    private lateinit var overlay: RelativeLayout
    private lateinit var card: RelativeLayout
    private lateinit var spRackets: Spinner
    private lateinit var spBalls: Spinner
    private lateinit var btnCancel: View
    private lateinit var btnConfirm: View

    // Inicijalizuje UI komponente, postavlja listenere za overlay/dugmad i pokreće animaciju ulaska
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_equipment)

        overlay     = findViewById(R.id.equipmentPopupOverlay)
        card        = findViewById(R.id.equipmentPopupCard)
        spRackets   = findViewById(R.id.spinnerRackets)
        spBalls     = findViewById(R.id.spinnerBalls)
        btnCancel   = findViewById(R.id.btnCancel)
        btnConfirm  = findViewById(R.id.btnConfirm)

        overlay.visibility = View.VISIBLE
        animateIn()

        overlay.setOnClickListener { v ->
            if (v.id == R.id.equipmentPopupOverlay) dismissCanceled()
        }
        card.setOnClickListener { /* consume */ }

        btnCancel.setOnClickListener { dismissCanceled() }
        btnConfirm.setOnClickListener {
            val rackets = spRackets.selectedItem.toString().toIntOrNull() ?: 0
            val balls   = spBalls.selectedItem.toString().toIntOrNull() ?: 0
            val data = Intent()
                .putExtra(EXTRA_RACKETS, rackets)
                .putExtra(EXTRA_BALLS, balls)
            setResult(RESULT_OK, data)
            animateOutAndFinish()
        }
        overridePendingTransition(0, 0)
    }

    // Animira pojavu popup-a sa fade-in efektom za overlay i slide-up + fade-in za karticu
    private fun animateIn() {
        overlay.alpha = 0f
        card.translationY = dp(40f)
        card.alpha = 0f

        overlay.animate().alpha(1f).setDuration(150).start()
        card.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    // Postavlja rezultat na RESULT_CANCELED i zatvara aktivnost sa animacijom izlaska
    private fun dismissCanceled() {
        setResult(RESULT_CANCELED)
        animateOutAndFinish()
    }

    // Animira izlazak popup-a sa fade-out i slide-down efektom i završava aktivnost
    private fun animateOutAndFinish() {
        overlay.animate().alpha(0f).setDuration(150).start()
        card.animate()
            .translationY(dp(40f))
            .alpha(0f)
            .setDuration(180)
            .withEndAction {
                finish()
                overridePendingTransition(0, 0)
            }
            .start()
    }

    // Konvertuje dp vrednost u piksele na osnovu gustine ekrana
    private fun dp(v: Float): Float {
        val d = resources.displayMetrics.density
        return v * d
    }
}
