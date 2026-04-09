package com.terenac.opencourt

import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import android.graphics.Color
import android.util.Log

class MessagesHistory : AppCompatActivity() {

    private lateinit var btnBack: MaterialButton
    private lateinit var rvThreads: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var adapter: ThreadsAdapter

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }
    private var listener: ListenerRegistration? = null

    private val nameCache = mutableMapOf<String, String>()
    private var currentThreads = mutableListOf<ThreadItem>()

    // Inicijalizuje UI, postavlja SwipeRefreshLayout, listener za SVE chat thread-ove sa real-time updates i prikazuje listu
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_history)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, ins ->
            val sys = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            ins
        }

        btnBack = findViewById(R.id.btnBack)
        btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        swipeRefresh = findViewById(R.id.swipeRefresh)
        swipeRefresh.setColorSchemeColors(
            Color.parseColor("#4FC3F7"),
            Color.parseColor("#29B6F6"),
            Color.parseColor("#03A9F4")
        )
        swipeRefresh.setOnRefreshListener {
            nameCache.clear()
            loadThreads()
        }

        rvThreads = findViewById(R.id.rvChatHistory)
        rvThreads.layoutManager = LinearLayoutManager(this)
        adapter = ThreadsAdapter { thread ->
            val i = android.content.Intent(this, MessagesActivity::class.java)
                .putExtra(MessagesActivity.EXTRA_CLUB_ID, thread.clubId)
                .putExtra(MessagesActivity.EXTRA_ADMIN_ID, thread.adminId)
                .putExtra(MessagesActivity.EXTRA_CONTACT_NAME, thread.contactName)
                .putExtra(MessagesActivity.EXTRA_PEER_USER_ID, thread.userId)
            startActivity(i)
        }
        rvThreads.adapter = adapter

        loadThreads()
    }

    // Postavlja real-time Firestore listener za chatove koji automatski ažurira listu pri svakoj promeni (nova poruka, seen status)
    private fun loadThreads() {
        val me = auth.currentUser?.uid ?: run {
            swipeRefresh.isRefreshing = false
            Toast.makeText(this, "Niste prijavljeni.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        listener?.remove()
        listener = db.collection("chats")
            .whereArrayContains("users", me)
            .addSnapshotListener { snap, error ->
                swipeRefresh.isRefreshing = false

                if (error != null) {
                    Log.e("MessagesHistory", "Firestore error", error)
                    return@addSnapshotListener
                }

                if (snap == null) {
                    Log.w("MessagesHistory", "Snapshot is null")
                    return@addSnapshotListener
                }

                currentThreads.clear()
                val userIdsToFetch = mutableSetOf<String>()

                snap.documents.forEach { d ->
                    val clubId = d.getString("clubId").orEmpty()
                    val adminId = d.getString("adminId").orEmpty()
                    val userId = d.getString("userId").orEmpty()
                    val lastMsg = d.getString("lastMessage").orEmpty()
                    val lastTs = d.getTimestamp("lastTs") ?: Timestamp.now()
                    val unreadFor = d.get("unreadFor") as? Map<*, *> ?: emptyMap<String, Int>()
                    val unreadMine = (unreadFor[me] as? Number)?.toInt() ?: 0

                    val otherUserId = if (me == adminId) userId else adminId
                    val cachedName = nameCache[otherUserId]

                    if (cachedName == null && otherUserId.isNotBlank()) {
                        userIdsToFetch.add(otherUserId)
                    }

                    currentThreads += ThreadItem(
                        id = d.id,
                        clubId = clubId,
                        adminId = adminId,
                        userId = userId,
                        lastMessage = lastMsg,
                        lastTs = lastTs,
                        unread = unreadMine,
                        contactName = cachedName ?: ""
                    )
                }

                currentThreads.sortByDescending { it.lastTs.seconds }
                adapter.submitList(currentThreads.toList())

                userIdsToFetch.forEach { otherUserId ->
                    fetchUserName(otherUserId) { realName ->
                        nameCache[otherUserId] = realName
                        updateThreadName(otherUserId, realName, me)
                    }
                }
            }
    }

    // Uklanja Firestore listener da spreči memory leak
    override fun onDestroy() {
        super.onDestroy()
        listener?.remove()
    }

    // Ponovo aktivira listener kada se korisnik vrati na ekran da osveži seen status
    override fun onResume() {
        super.onResume()
        val me = auth.currentUser?.uid
        if (me != null && listener == null) {
            loadThreads()
        }
    }

    // Ažurira ime u trenutnoj listi thread-ova i osvežava adapter sa novom listom
    private fun updateThreadName(otherUserId: String, realName: String, myUid: String) {
        var updated = false
        val newList = currentThreads.map { item ->
            val itemOtherUserId = if (myUid == item.adminId) item.userId else item.adminId
            if (itemOtherUserId == otherUserId) {
                updated = true
                item.copy(contactName = realName)
            } else {
                item
            }
        }

        if (updated) {
            currentThreads.clear()
            currentThreads.addAll(newList)
            adapter.submitList(newList)
        }
    }

    // Ekstraktuje fallback ime iz email adrese zamenjujući tačke i donje crte sa razmacima i kapitalizuje prvo slovo
    private fun fallbackFromEmail(email: String?): String {
        if (email.isNullOrBlank()) return "Korisnik"
        val local = email.substringBefore('@').replace(".", " ").replace("_", " ").trim()
        return local.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    // Dohvata ime korisnika iz Firestore (firstName + lastName obavezno, displayName ili email fallback) i kešira rezultat
    private fun fetchUserName(uid: String, onReady: (String) -> Unit) {
        if (uid.isBlank()) {
            onReady("Nepoznat korisnik")
            return
        }

        nameCache[uid]?.let {
            onReady(it)
            return
        }

        Log.d("MessagesHistory", "Fetching name for UID: $uid")

        db.collection("users").document(uid).get()
            .addOnSuccessListener { d ->
                if (!d.exists()) {
                    Log.w("MessagesHistory", "User document does not exist for UID: $uid")
                    val fallback = "Korisnik $uid"
                    nameCache[uid] = fallback
                    onReady(fallback)
                    return@addOnSuccessListener
                }

                val first = d.getString("firstName")?.trim().orEmpty()
                val last  = d.getString("lastName")?.trim().orEmpty()
                val dn    = d.getString("displayName")?.trim().orEmpty()
                val mail  = d.getString("email")?.trim()

                Log.d("MessagesHistory", "User data - firstName: '$first', lastName: '$last', displayName: '$dn', email: '$mail'")

                val name = when {
                    first.isNotBlank() && last.isNotBlank() -> "$first $last"
                    first.isNotBlank() -> first
                    dn.isNotBlank() -> dn
                    mail != null -> fallbackFromEmail(mail)
                    else -> "Korisnik ${uid.take(8)}"
                }

                Log.d("MessagesHistory", "Final name for $uid: '$name'")
                nameCache[uid] = name
                onReady(name)
            }
            .addOnFailureListener { e ->
                Log.e("MessagesHistory", "Failed to fetch user $uid", e)
                val fallback = "Korisnik ${uid.take(8)}"
                nameCache[uid] = fallback
                onReady(fallback)
            }
    }

    data class ThreadItem(
        val id: String,
        val clubId: String,
        val adminId: String,
        val userId: String,
        val lastMessage: String,
        val lastTs: Timestamp,
        val unread: Int,
        val contactName: String
    )

    private class ThreadDiff : DiffUtil.ItemCallback<ThreadItem>() {
        // Proverava da li su thread-ovi isti poređenjem njihovih ID-ova
        override fun areItemsTheSame(oldItem: ThreadItem, newItem: ThreadItem) = oldItem.id == newItem.id
        // Proverava da li su sadržaji thread-ova identični (uključujući unread brojač, lastMessage i contactName)
        override fun areContentsTheSame(oldItem: ThreadItem, newItem: ThreadItem) = oldItem == newItem
    }

    private class ThreadsAdapter(
        private val onClick: (ThreadItem) -> Unit
    ) : ListAdapter<ThreadItem, ThreadsAdapter.VH>(ThreadDiff()) {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view.findViewById(android.R.id.text1)
            val tvSubtitle: TextView = view.findViewById(android.R.id.text2)
            val unreadDot: View = view.findViewById(R.id.tvUnreadBadge)
        }

        // Kreira novi ViewHolder inflating-om item_chat_thread layout-a
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chat_thread, parent, false)
            return VH(v)
        }

        // Prikazuje thread sa kontakt imenom, poslednjom porukom i vremenom, bold tekst i unread badge sa zlatnim background-om za neseenovane poruke
        override fun onBindViewHolder(holder: VH, position: Int) {
            val thread = getItem(position)
            val time = DateFormat.format("dd.MM. HH:mm", thread.lastTs.toDate()).toString()

            val titleText = if (thread.contactName.isBlank()) "Učitavanje..." else thread.contactName

            if (thread.unread > 0) {
                val ss = SpannableString(titleText)
                ss.setSpan(
                    StyleSpan(android.graphics.Typeface.BOLD),
                    0,
                    titleText.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                holder.tvTitle.text = ss
                holder.tvTitle.setTextColor(Color.parseColor("#FFFFFF"))

                holder.unreadDot.backgroundTintList = null
                holder.unreadDot.setBackgroundResource(R.drawable.golden_glow_circle)
                holder.unreadDot.visibility = View.VISIBLE
            } else {
                holder.tvTitle.text = titleText
                holder.tvTitle.setTextColor(Color.parseColor("#A0A0A0"))

                holder.unreadDot.visibility = View.GONE
                holder.unreadDot.setBackgroundResource(0)
            }

            holder.tvSubtitle.text = "${thread.lastMessage}  —  $time"

            holder.itemView.setOnClickListener { onClick(thread) }
        }
    }
}
