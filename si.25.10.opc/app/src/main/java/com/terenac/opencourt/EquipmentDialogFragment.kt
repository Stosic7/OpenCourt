package com.terenac.opencourt

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.RelativeLayout
import android.widget.Spinner
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment

class EquipmentDialogFragment : DialogFragment() {

    companion object {
        const val RESULT_KEY = "equipment_result"
        const val KEY_RACKETS = "rackets"
        const val KEY_BALLS = "balls"

        // Factory metod za kreiranje nove instance EquipmentDialogFragment-a
        fun newInstance(): EquipmentDialogFragment = EquipmentDialogFragment()
    }

    private lateinit var overlay: RelativeLayout
    private lateinit var card: RelativeLayout
    private lateinit var spRackets: Spinner
    private lateinit var spBalls: Spinner
    private lateinit var btnCancel: View
    private lateinit var btnConfirm: View

    // Postavlja stil dijaloga na transparentan bez naslova
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, android.R.style.Theme_Translucent_NoTitleBar)
    }

    // Inflating-uje layout, inicijalizuje komponente, postavlja listenere i pokreće animaciju ulaska
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.activity_equipment, container, false)

        overlay     = view.findViewById(R.id.equipmentPopupOverlay)
        card        = view.findViewById(R.id.equipmentPopupCard)
        spRackets   = view.findViewById(R.id.spinnerRackets)
        spBalls     = view.findViewById(R.id.spinnerBalls)
        btnCancel   = view.findViewById(R.id.btnCancel)
        btnConfirm  = view.findViewById(R.id.btnConfirm)
        overlay.visibility = View.VISIBLE
        animateIn()

        overlay.setOnClickListener { v ->
            if (v.id == R.id.equipmentPopupOverlay) dismissWithCanceled()
        }
        card.setOnClickListener { /* consume */ }

        btnCancel.setOnClickListener { dismissWithCanceled() }
        btnConfirm.setOnClickListener {
            val rackets = spRackets.selectedItem.toString().toIntOrNull() ?: 0
            val balls   = spBalls.selectedItem.toString().toIntOrNull() ?: 0
            parentFragmentManager.setFragmentResult(
                RESULT_KEY,
                bundleOf(KEY_RACKETS to rackets, KEY_BALLS to balls)
            )
            animateOutAndDismiss()
        }

        return view
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

    // Zatvara dialog sa animacijom izlaska bez slanja rezultata
    private fun dismissWithCanceled() {
        animateOutAndDismiss()
    }

    // Animira izlazak popup-a sa fade-out i slide-down efektom i zatvara dialog
    private fun animateOutAndDismiss() {
        overlay.animate().alpha(0f).setDuration(150).start()
        card.animate()
            .translationY(dp(40f))
            .alpha(0f)
            .setDuration(180)
            .withEndAction { dismissAllowingStateLoss() }
            .start()
    }

    // Konvertuje dp vrednost u piksele na osnovu gustine ekrana
    private fun dp(v: Float): Float = v * (resources.displayMetrics.density)
}
