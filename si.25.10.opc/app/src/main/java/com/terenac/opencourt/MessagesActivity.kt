package com.terenac.opencourt

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions

class MessagesActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CLUB_ID = "clubId"
        const val EXTRA_CLUB_NAME = "clubName"
        const val EXTRA_ADMIN_ID = "adminId"
        const val EXTRA_CONTACT_NAME = "contactName"
        const val EXTRA_PEER_USER_ID = "peerUserId"
    }

    private lateinit var btnBack: MaterialButton
    private lateinit var tvContactName: TextView
    private lateinit var tvTypingIndicator: TextView
    private lateinit var rvMessages: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: MaterialButton

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }
    private var messagesListener: ListenerRegistration? = null
    private var chatMetaListener: ListenerRegistration? = null
    private var typingListener: ListenerRegistration? = null

    private lateinit var clubId: String
    private lateinit var adminId: String
    private lateinit var chatId: String
    private lateinit var otherUserId: String
    private var contactName: String = ""

    private var myUid: String = ""
    private var currentUnreadForMe: Int = 0

    private lateinit var adapter: MessagesAdapter

    // Inicijalizuje chat UI, proverava autentifikaciju, određuje chat partnera (admin ili korisnik) i setup-uje listenere za poruke, typing indikator i unread brojač
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_messages)

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, ins ->
            val sys = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = ins.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(sys.left, sys.top, sys.right, ime.bottom.coerceAtLeast(sys.bottom))
            ins
        }

        val me = auth.currentUser?.uid ?: run {
            Toast.makeText(this, "Niste prijavljeni.", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        myUid = me
        clubId = intent.getStringExtra(EXTRA_CLUB_ID) ?: run { finish(); return }
        adminId = intent.getStringExtra(EXTRA_ADMIN_ID) ?: run { finish(); return }
        contactName = intent.getStringExtra(EXTRA_CONTACT_NAME) ?: intent.getStringExtra(EXTRA_CLUB_NAME).orEmpty()

        val iAmAdmin = me == adminId
        otherUserId = if (iAmAdmin) {
            intent.getStringExtra(EXTRA_PEER_USER_ID) ?: run {
                Toast.makeText(this, "Nedostaje korisnik za razgovor.", Toast.LENGTH_SHORT).show()
                finish(); return
            }
        } else {
            adminId
        }

        chatId = stableChatId(clubId, adminId, if (iAmAdmin) otherUserId else me)

        btnBack = findViewById(R.id.btnBack)
        tvContactName = findViewById(R.id.tvContactName)
        tvTypingIndicator = findViewById(R.id.tvTypingIndicator)
        rvMessages = findViewById(R.id.rvMessages)
        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)

        tvContactName.text = if (contactName.isNotBlank()) contactName else "Poruke"

        btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        adapter = MessagesAdapter(myUid)
        rvMessages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        rvMessages.adapter = adapter

        rvMessages.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom < oldBottom) {
                rvMessages.post {
                    val itemCount = adapter.itemCount
                    if (itemCount > 0) {
                        rvMessages.scrollToPosition(itemCount - 1)
                    }
                }
            }
        }

        btnSend.setOnClickListener { sendMessage() }
        etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendMessage(); true } else false
        }

        setupTypingIndicator()
        listenMessages()
        listenChatMetaAndUnread()
        listenTypingStatus()
        clearMyUnread()
    }

    // Uklanja sve Firestore listenere i zaustavlja typing indikator da spreči memory leak
    override fun onDestroy() {
        super.onDestroy()
        messagesListener?.remove()
        chatMetaListener?.remove()
        typingListener?.remove()
        stopTyping()
    }

    // Dodaje TextWatcher koji startuje/zaustavlja typing indikator na osnovu toga da li ima teksta u polju
    private fun setupTypingIndicator() {
        etMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!s.isNullOrEmpty()) {
                    startTyping()
                } else {
                    stopTyping()
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    // Postavlja typing status na true u Firestore chats dokumentu za trenutnog korisnika
    private fun startTyping() {
        db.collection("chats").document(chatId)
            .update("typing.$myUid", true)
            .addOnFailureListener { /* ignore */ }
    }

    // Postavlja typing status na false u Firestore chats dokumentu za trenutnog korisnika
    private fun stopTyping() {
        db.collection("chats").document(chatId)
            .update("typing.$myUid", false)
            .addOnFailureListener { /* ignore */ }
    }

    // Postavlja real-time listener koji prikazuje/sakriva typing indikator kada drugi korisnik kuca
    private fun listenTypingStatus() {
        typingListener?.remove()
        typingListener = db.collection("chats").document(chatId)
            .addSnapshotListener { snap, _ ->
                val typingMap = snap?.get("typing") as? Map<*, *> ?: emptyMap<String, Boolean>()
                val isOtherTyping = (typingMap[otherUserId] as? Boolean) == true
                tvTypingIndicator.visibility = if (isOtherTyping) View.VISIBLE else View.GONE
            }
    }

    // Šalje poruku u Firestore messages subkolekciju, ažurira chat meta podatke (lastMessage, timestamp) i inkrementira unread brojač za primaoca
    private fun sendMessage() {
        val me = myUid
        val text = etMessage.text.toString().trim()
        if (text.isBlank()) return

        val chatRef = db.collection("chats").document(chatId)
        val msgsRef = chatRef.collection("messages")

        val msg = hashMapOf(
            "senderId" to me,
            "text" to text,
            "ts" to FieldValue.serverTimestamp(),
            "seen" to false
        )
        msgsRef.add(msg)
            .addOnSuccessListener {
                etMessage.setText("")
                stopTyping()
                val userId = if (me == adminId) otherUserId else me
                val users = listOf(adminId, userId)
                val unreadMapKeyForOther = if (me == adminId) userId else adminId
                val updates = hashMapOf(
                    "clubId" to clubId,
                    "adminId" to adminId,
                    "userId" to userId,
                    "users" to users,
                    "lastMessage" to text,
                    "lastTs" to FieldValue.serverTimestamp(),
                    "unreadFor.$unreadMapKeyForOther" to FieldValue.increment(1)
                )
                chatRef.set(updates, SetOptions.merge())
            }
    }

    // Postavlja real-time listener za poruke, sortira ih po timestamp-u, automatski scroll-uje na dno i označava primljene poruke kao seen
    private fun listenMessages() {
        val chatRef = db.collection("chats").document(chatId)
        messagesListener?.remove()
        messagesListener = chatRef.collection("messages")
            .orderBy("ts")
            .addSnapshotListener { snap, _ ->
                val list = ArrayList<ChatMessage>(snap?.size() ?: 0)
                snap?.documents?.forEach { d ->
                    val t = d.getTimestamp("ts") ?: Timestamp.now()
                    val msg = ChatMessage(
                        id = d.id,
                        senderId = d.getString("senderId").orEmpty(),
                        text = d.getString("text").orEmpty(),
                        ts = t,
                        seen = d.getBoolean("seen") ?: false
                    )
                    list += msg
                }

                adapter.submitList(list) {
                    rvMessages.scrollToPosition(maxOf(list.size - 1, 0))
                }

                list.filter { it.senderId == otherUserId && !it.seen }.forEach { msg ->
                    chatRef.collection("messages").document(msg.id)
                        .update("seen", true)
                }

                clearMyUnread()
            }
    }

    // Postavlja listener za chat meta podatke koji prati unread brojač za trenutnog korisnika
    private fun listenChatMetaAndUnread() {
        chatMetaListener?.remove()
        chatMetaListener = db.collection("chats").document(chatId)
            .addSnapshotListener { snap, _ ->
                val unreadFor = snap?.get("unreadFor") as? Map<*, *> ?: emptyMap<String, Int>()
                val mine = (unreadFor[myUid] as? Number)?.toInt() ?: 0
                currentUnreadForMe = mine
            }
    }

    // Resetuje unread brojač na 0 za trenutnog korisnika u chats dokumentu
    private fun clearMyUnread() {
        db.collection("chats").document(chatId)
            .update("unreadFor.$myUid", 0)
            .addOnFailureListener { /* ignore */ }
    }

    data class ChatMessage(
        val id: String,
        val senderId: String,
        val text: String,
        val ts: Timestamp,
        val seen: Boolean
    )

    private class MsgDiff : DiffUtil.ItemCallback<ChatMessage>() {
        // Proverava da li su poruke iste poređenjem njihovih ID-ova
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage) = oldItem.id == newItem.id
        // Proverava da li su sadržaji poruka identični (uključujući seen status)
        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage) = oldItem == newItem
    }

    private inner class MessagesAdapter(
        private val myUid: String
    ) : ListAdapter<ChatMessage, MessagesAdapter.VH>(MsgDiff()) {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val root: ViewGroup = view as ViewGroup
            val cardReceived: com.google.android.material.card.MaterialCardView = view.findViewById(R.id.receivedMessageCard)
            val tvReceived: TextView = view.findViewById(R.id.tvReceivedMessage)
            val tvReceivedTime: TextView = view.findViewById(R.id.tvReceivedTime)
            val cardSent: com.google.android.material.card.MaterialCardView = view.findViewById(R.id.sentMessageCard)
            val tvSent: TextView = view.findViewById(R.id.tvSentMessage)
            val tvSentTime: TextView = view.findViewById(R.id.tvSentTime)
            val tvSeenStatus: TextView = view.findViewById(R.id.tvSeenStatus)
        }

        // Kreira novi ViewHolder inflating-om item_message layout-a
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
            return VH(v)
        }

        // Prikazuje poruku kao sent (desno) ili received (levo), formatira vreme i prikazuje seen status (✓/✓✓) za poslate poruke
        override fun onBindViewHolder(holder: VH, position: Int) {
            val m = getItem(position)
            val time = DateFormat.format("HH:mm", m.ts.toDate()).toString()

            val isMine = m.senderId == myUid
            if (isMine) {
                holder.cardSent.visibility = View.VISIBLE
                holder.cardReceived.visibility = View.GONE
                holder.tvSent.text = m.text
                holder.tvSentTime.text = time

                if (m.seen) {
                    holder.tvSeenStatus.visibility = View.VISIBLE
                    holder.tvSeenStatus.text = "✓✓"
                    holder.tvSeenStatus.setTextColor(android.graphics.Color.parseColor("#4FC3F7"))
                } else {
                    holder.tvSeenStatus.visibility = View.VISIBLE
                    holder.tvSeenStatus.text = "✓"
                    holder.tvSeenStatus.setTextColor(android.graphics.Color.parseColor("#90CAF9"))
                }
            } else {
                holder.cardSent.visibility = View.GONE
                holder.cardReceived.visibility = View.VISIBLE
                holder.tvReceived.text = m.text
                holder.tvReceivedTime.text = time
            }
        }
    }

    // Generiše jedinstveni, stabilan chat ID u formatu "club__clubId__adminId__userId" sa sortiranim user ID-ovima
    private fun stableChatId(clubId: String, adminId: String, userId: String): String {
        val p = listOf(adminId, userId).sorted().joinToString("__")
        return "club__${clubId}__${p}"
    }
}
