package com.terenac.opencourt

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.location.Geocoder
import android.location.Address
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.ListPopupWindow
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.*
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import android.util.Base64
import com.google.android.gms.maps.model.LatLng

class AddClubActivity : AppCompatActivity() {

    private val db by lazy { FirebaseFirestore.getInstance() }
    private val auth by lazy { FirebaseAuth.getInstance() }

    private lateinit var etClubName: EditText
    private lateinit var etAddress: EditText
    private lateinit var btnSave: View
    private lateinit var btnBack: MaterialButton
    private lateinit var btnAddImage: View
    private lateinit var btnRemoveImage: View
    private lateinit var ivClubImage: ImageView
    private lateinit var imagePreviewCard: View

    private lateinit var geocoder: Geocoder
    private lateinit var placesClient: PlacesClient
    private var sessionToken: AutocompleteSessionToken? = null

    private val nisSW = LatLng(43.2500, 21.7800)
    private val nisNE = LatLng(43.3800, 22.0200)
    private val nisBounds by lazy { RectangularBounds.newInstance(nisSW, nisNE) }

    private lateinit var listPopup: ListPopupWindow
    private lateinit var suggestionsAdapter: ArrayAdapter<String>
    private val displaySuggestions = mutableListOf<String>()
    private val rawSuggestions = mutableListOf<Suggestion>()

    private var picked: PickedPlace? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var debounceTask: Runnable? = null
    private var pendingBg: Future<*>? = null
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val debounceMs = 250L

    private val darkBlue = Color.parseColor("#0F2C3D")
    private val midBlue  = Color.parseColor("#16465E")
    private val lightBlue = Color.parseColor("#2A6A87")
    private val textWhite = Color.WHITE

    private var pickedImageUri: Uri? = null
    private var imageDataUrl: String? = null
    private var thumbDataUrl: String? = null

    // Callback koji obrađuje izabranu sliku, uzima persistable permission i pokreće pozadinsku obradu slike
    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
            pickedImageUri = uri
            ivClubImage.setImageURI(uri)
            imagePreviewCard.visibility = View.VISIBLE
            bgExecutor.execute {
                try {
                    val (full, thumb) = buildDataUrlsFromUri(uri)
                    mainHandler.post {
                        imageDataUrl = full
                        thumbDataUrl = thumb
                        Toast.makeText(this, "Slika učitana.", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    mainHandler.post {
                        Toast.makeText(this, "Greška pri obradi slike: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                        pickedImageUri = null; imageDataUrl = null; thumbDataUrl = null
                        imagePreviewCard.visibility = View.GONE
                        ivClubImage.setImageDrawable(null)
                    }
                }
            }
        }
    }

    // Inicijalizuje UI komponente, Geocoder, Places API, i postavlja listenere za dugmad i unos teksta
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_club)

        geocoder = Geocoder(this, Locale.getDefault())
        initPlacesClient()

        etClubName = findViewById(R.id.etClubName)
        etAddress = findViewById(R.id.etAddress)
        btnSave = findViewById(R.id.btnSave)
        btnBack = findViewById(R.id.btnBack)
        btnAddImage = findViewById(R.id.btnAddImage)
        btnRemoveImage = findViewById(R.id.btnRemoveImage)
        ivClubImage = findViewById(R.id.ivClubImage)
        imagePreviewCard = findViewById(R.id.imagePreviewCard)

        btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        btnAddImage.setOnClickListener {
            runCatching { pickImage.launch(arrayOf("image/*")) }
                .onFailure { Toast.makeText(this, "Nije moguće otvoriti galeriju.", Toast.LENGTH_SHORT).show() }
        }
        btnRemoveImage.setOnClickListener {
            pickedImageUri = null; imageDataUrl = null; thumbDataUrl = null
            imagePreviewCard.visibility = View.GONE
            ivClubImage.setImageDrawable(null)
        }

        suggestionsAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, displaySuggestions) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = super.getView(position, convertView, parent) as TextView
                tv.setTextColor(textWhite)
                tv.textSize = 16f
                tv.background = ColorDrawable(Color.TRANSPARENT)
                tv.setPadding(tv.paddingLeft, (tv.paddingTop * 1.2).roundToInt(), tv.paddingRight, (tv.paddingBottom * 1.2).roundToInt())
                return tv
            }
        }
        listPopup = ListPopupWindow(this).apply {
            anchorView = etAddress
            inputMethodMode = ListPopupWindow.INPUT_METHOD_NOT_NEEDED
            setAdapter(suggestionsAdapter)
            setOnItemClickListener { _, _, position, _ ->
                val pick = rawSuggestions.getOrNull(position) ?: return@setOnItemClickListener
                if (pick.placeId == "MANUAL_INPUT") {
                    val typed = etAddress.text?.toString()?.trim().orEmpty()
                    geocodeAddressFallback(typed) { resolved ->
                        if (resolved != null) {
                            val labelResolved = resolved.fullAddress
                            etAddress.setText(labelResolved)
                            etAddress.setSelection(labelResolved.length)
                            etAddress.error = null
                            picked = resolved
                        } else {
                            picked = null
                            etAddress.error = "Nije moguće verifikovati adresu (pokušajte drugačije formulisano)."
                        }
                    }
                    dismiss()
                    return@setOnItemClickListener
                }
                val label = listLabel(pick)
                etAddress.setText(label)
                etAddress.setSelection(label.length)
                etAddress.error = null
                fetchPlaceDetails(pick.placeId)
                dismiss()
            }
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f
                setColor(darkBlue)
                setStroke(2, lightBlue)
            }
            setBackgroundDrawable(bg)
            setListSelector(ColorDrawable(midBlue))
        }

        etAddress.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                picked = null
                scheduleAutocomplete(s?.toString().orEmpty())
            }
        })
        etAddress.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) listPopup.dismiss() }
        etAddress.imeOptions = EditorInfo.IME_ACTION_SEARCH

        btnSave.setOnClickListener { saveClub() }
    }

    // Otkazuje pozadinske zadatke i zatvara popup sa sugestijama
    override fun onStop() {
        super.onStop()
        pendingBg?.cancel(true)
        listPopup.dismiss()
    }

    // Inicijalizuje Google Places API klijent sa API ključem iz manifesta i kreira session token
    private fun initPlacesClient() {
        val apiKey = runCatching {
            val ai = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            val md = ai.metaData
            md?.getString("com.google.android.geo.API_KEY")
        }.getOrNull().orEmpty()

        if (!Places.isInitialized()) {
            Places.initialize(applicationContext, apiKey, Locale("sr"))
        }
        placesClient = Places.createClient(this)
        sessionToken = AutocompleteSessionToken.newInstance()
    }

    // Ručno geokodira adresu koristeći Geocoder kao fallback za neuspele Places API sugestije
    private fun geocodeAddressFallback(raw: String, onDone: (PickedPlace?) -> Unit) {
        try {
            val geo = Geocoder(this, Locale("sr", "RS"))
            val query = if (raw.contains("niš", true) || raw.contains("nis", true)) raw else "$raw, Niš, Srbija"
            val res: List<Address> = geo.getFromLocationName(query, 1) ?: emptyList()
            val a = res.firstOrNull()
            if (a != null && a.hasLatitude() && a.hasLongitude()) {
                val full = a.getAddressLine(0) ?: raw
                onDone(PickedPlace(fullAddress = full, lat = a.latitude, lng = a.longitude, placeId = "MANUAL_GEOCODER"))
            } else {
                onDone(null)
            }
        } catch (_: Exception) {
            onDone(null)
        }
    }

    // Zakazuje autocomplete pretragu sa debounce-om od 250ms, odbacuje ako je upit kraći od 3 karaktera
    private fun scheduleAutocomplete(query: String) {
        debounceTask?.let { mainHandler.removeCallbacks(it) }
        if (query.length < 3) {
            rawSuggestions.clear(); displaySuggestions.clear()
            suggestionsAdapter.notifyDataSetChanged()
            listPopup.dismiss()
            return
        }
        debounceTask = Runnable { fetchAutocomplete(query) }
        mainHandler.postDelayed(debounceTask!!, debounceMs)
    }

    // Dohvata autocomplete sugestije iz Places API filtrirane po Nišu i dodaje opciju za ručni unos
    private fun fetchAutocomplete(query: String) {
        val token = sessionToken ?: AutocompleteSessionToken.newInstance().also { sessionToken = it }
        val req = FindAutocompletePredictionsRequest.builder()
            .setQuery(query)
            .setTypeFilter(TypeFilter.ADDRESS)
            .setCountries(listOf("RS"))
            .setLocationBias(nisBounds)
            .setSessionToken(token)
            .build()

        placesClient.findAutocompletePredictions(req)
            .addOnSuccessListener { resp ->
                rawSuggestions.clear()
                displaySuggestions.clear()
                resp.autocompletePredictions.forEach { p ->
                    val primary = p.getPrimaryText(null).toString()
                    val secondary = p.getSecondaryText(null).toString()
                    val low = (primary + " " + secondary).lowercase(Locale.getDefault())
                    val ok = low.contains("niš") || low.contains("nis") || low.contains("niška banja") || low.contains("niska banja")
                    if (ok) {
                        val sug = Suggestion(p.placeId, primary, secondary)
                        rawSuggestions += sug
                        displaySuggestions += listLabel(sug)
                    }
                }
                if (displaySuggestions.isEmpty()) {
                    val label = "Koristi unetu adresu:  $query"
                    rawSuggestions += Suggestion(placeId = "MANUAL_INPUT", primary = query, secondary = "Niš, Srbija")
                    displaySuggestions += label
                }
                suggestionsAdapter.notifyDataSetChanged()
                if (displaySuggestions.isNotEmpty()) {
                    listPopup.width = etAddress.width
                    if (!listPopup.isShowing) {
                        listPopup.show()
                    }
                    etAddress.requestFocus()
                } else {
                    listPopup.dismiss()
                }
            }
            .addOnFailureListener {
                rawSuggestions.clear()
                displaySuggestions.clear()
                val label = "Koristi unetu adresu:  $query"
                rawSuggestions += Suggestion(placeId = "MANUAL_INPUT", primary = query, secondary = "Niš, Srbija")
                displaySuggestions += label
                suggestionsAdapter.notifyDataSetChanged()
                if (displaySuggestions.isNotEmpty()) {
                    listPopup.width = etAddress.width
                    if (!listPopup.isShowing) {
                        listPopup.show()
                    }
                    etAddress.requestFocus()
                } else {
                    listPopup.dismiss()
                }
            }
    }

    // Dohvata detaljne informacije (lat/lng i adresu) za izabrano mesto pomoću Places API
    private fun fetchPlaceDetails(placeId: String) {
        val fields = listOf(Place.Field.LAT_LNG, Place.Field.ADDRESS)
        val req = FetchPlaceRequest.newInstance(placeId, fields)
        placesClient.fetchPlace(req)
            .addOnSuccessListener { resp ->
                val place = resp.place
                val latLng = place.latLng
                val addr = place.address
                if (latLng != null && !addr.isNullOrBlank()) {
                    picked = PickedPlace(addr, latLng.latitude, latLng.longitude, placeId)
                } else {
                    picked = null
                    Toast.makeText(this, "Nije moguće verifikovati adresu.", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener {
                picked = null
                Toast.makeText(this, "Greška pri dohvaćanju adrese.", Toast.LENGTH_SHORT).show()
            }
    }

    // Validira unos, proverava da li je adresa izabrana iz sugestija i čuva klub u Firestore
    private fun saveClub() {
        val name = etClubName.text.toString().trim()
        val addressText = etAddress.text.toString().trim()

        if (name.isEmpty()) { Toast.makeText(this, "Unesite naziv kluba", Toast.LENGTH_SHORT).show(); return }
        if (addressText.isEmpty()) { Toast.makeText(this, "Unesite adresu", Toast.LENGTH_SHORT).show(); return }

        val uid = auth.currentUser?.uid ?: run { Toast.makeText(this, "Niste prijavljeni.", Toast.LENGTH_SHORT).show(); return }

        val pick = picked
        val okManual = pick != null && pick.placeId == "MANUAL_GEOCODER" && addressText.equals(pick.fullAddress, true)
        val okPlaces = pick != null && addressText.equals(pick.fullAddress, true)
        if (!(okManual || okPlaces)) {
            etAddress.error = "Izaberite adresu iz liste"
            Toast.makeText(this, "Molimo izaberite validnu adresu iz sugestija.", Toast.LENGTH_LONG).show()
            return
        }

        val data = hashMapOf(
            "name" to name,
            "address" to pick!!.fullAddress,
            "lat" to pick.lat,
            "lng" to pick.lng,
            "ownerAdminId" to uid,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
            "thumbDataUrl" to thumbDataUrl,
            "imageDataUrl" to imageDataUrl
        )

        db.collection("clubs")
            .add(data)
            .addOnSuccessListener {
                Toast.makeText(this, "Klub sačuvan.", Toast.LENGTH_SHORT).show()
                finish()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Greška: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
    }


    private data class Suggestion(
        val placeId: String,
        val primary: String,
        val secondary: String
    )

    private data class PickedPlace(
        val fullAddress: String,
        val lat: Double,
        val lng: Double,
        val placeId: String
    )

    // Formatira sugestiju za prikaz u listi spajajući primarni i sekundarni tekst
    private fun listLabel(s: Suggestion): String =
        if (s.secondary.isBlank()) s.primary else "${s.primary}, ${s.secondary}"

    // Pretvara URI slike u data URL format u dve veličine: punu (1024px) i thumbnail (256px)
    private fun buildDataUrlsFromUri(uri: Uri): Pair<String?, String?> {
        val original = decodeScaledBitmapFromUri(uri, maxDim = 2048) ?: return Pair(null, null)
        val full = scaleBitmapMaintainingAspect(original, 1024)
        val fullDataUrl = toJpegDataUrl(full, quality = 80)
        val thumb = scaleBitmapMaintainingAspect(original, 256)
        val thumbDataUrl = toJpegDataUrl(thumb, quality = 70)
        if (full !== original) original.recycle()
        return Pair(fullDataUrl, thumbDataUrl)
    }

    // Dekodira bitmap iz URI sa automatskim skaliranjem (inSampleSize) da ne prelazi maxDim
    private fun decodeScaledBitmapFromUri(uri: Uri, maxDim: Int): Bitmap? {
        val opts1 = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(uri).use { s -> BitmapFactory.decodeStream(s, null, opts1) }
        var inSample = 1
        val w = opts1.outWidth
        val h = opts1.outHeight
        if (w > 0 && h > 0) {
            val largest = max(w, h).toFloat()
            if (largest > maxDim) inSample = (largest / maxDim).roundToInt().coerceAtLeast(1)
        }
        val opts2 = BitmapFactory.Options().apply { inSampleSize = inSample }
        return openStream(uri).use { s -> BitmapFactory.decodeStream(s, null, opts2) }
    }

    // Skalira bitmap na maxSide piksela zadržavajući aspect ratio
    private fun scaleBitmapMaintainingAspect(src: Bitmap, maxSide: Int): Bitmap {
        val w = src.width
        val h = src.height
        val scale = min(1f, maxSide.toFloat() / max(w, h).toFloat())
        if (scale >= 0.999f) return src
        val nw = (w * scale).roundToInt().coerceAtLeast(1)
        val nh = (h * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    // Konvertuje bitmap u JPEG data URL sa Base64 enkodingom i specificiranim kvalitetom
    private fun toJpegDataUrl(bmp: Bitmap, quality: Int): String {
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(40, 95), baos)
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        return "data:image/jpeg;base64,$b64"
    }

    // Otvara InputStream iz URI-ja ili baca exception ako nije uspešno
    private fun openStream(uri: Uri): InputStream =
        contentResolver.openInputStream(uri) ?: throw IllegalStateException("Cannot open stream")
}
