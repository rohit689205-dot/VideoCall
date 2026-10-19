package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.ChatMessage
import com.example.data.model.MeetingHistoryItem
import com.example.data.model.RoomParticipant
import com.example.data.model.RoomSession
import com.example.data.model.SignalMessage
import com.example.data.model.UserProfile
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

class CallRepository(
    private val db: FirebaseFirestore
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth = Firebase.auth

    fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    fun currentUserIdOrNull(): String? = auth.currentUser?.uid
    fun currentUserDisplayName(): String = auth.currentUser?.displayName ?: "User"
    fun currentUserEmail(): String = auth.currentUser?.email ?: ""

    // --- User Profile ---

    suspend fun syncUserProfile(): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        val payload = mapOf(
            "userId" to uid,
            "displayName" to currentUserDisplayName(),
            "email" to currentUserEmail(),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.set(payload).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    // --- Rooms ---

    suspend fun createRoom(title: String): Result<RoomSession> = runCatching {
        val uid = requireUserId()
        // Generate formatted room code e.g. vid-839-201
        val randomSuffix = (100000..999999).random()
        val roomId = "vid-${randomSuffix.toString().substring(0, 3)}-${randomSuffix.toString().substring(3)}"
        val docRef = db.collection("rooms").document(roomId)

        val roomData = mapOf(
            "id" to roomId,
            "hostId" to uid,
            "hostName" to currentUserDisplayName(),
            "title" to title.ifBlank { "Quick Meeting" },
            "status" to "active",
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        try {
            docRef.set(roomData).await()
            RoomSession(
                id = roomId,
                hostId = uid,
                hostName = currentUserDisplayName(),
                title = title.ifBlank { "Quick Meeting" },
                status = "active"
            )
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            throw e
        }
    }

    suspend fun getRoom(roomId: String): Result<RoomSession?> = runCatching {
        val docRef = db.collection("rooms").document(roomId)
        try {
            val snapshot = docRef.get().await()
            if (snapshot.exists()) {
                snapshot.toObject(RoomSession::class.java)
            } else null
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            throw e
        }
    }

    fun observeRoom(roomId: String): Flow<RoomSession?> = flow {
        val docRef = db.collection("rooms").document(roomId)
        emitAll(
            docRef.snapshots()
                .map { snapshot ->
                    if (snapshot.exists()) snapshot.toObject(RoomSession::class.java) else null
                }
                .catch { error ->
                    if (error is Exception) handleFirestoreError(error, OperationType.GET, docRef.path)
                    throw error
                }
        )
    }

    suspend fun endRoom(roomId: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("rooms").document(roomId)
        try {
            docRef.update("status", "ended", "updatedAt", FieldValue.serverTimestamp()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            throw e
        }
    }

    // --- Participants ---

    suspend fun joinRoom(
        roomId: String,
        displayName: String,
        isHost: Boolean,
        isAudioMuted: Boolean,
        isVideoOff: Boolean
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val partRef = db.collection("rooms").document(roomId).collection("participants").document(uid)
        val payload = mapOf(
            "userId" to uid,
            "displayName" to displayName.ifBlank { currentUserDisplayName() },
            "isAudioMuted" to isAudioMuted,
            "isVideoOff" to isVideoOff,
            "isHost" to isHost,
            "joinedAt" to FieldValue.serverTimestamp(),
            "lastSeen" to FieldValue.serverTimestamp()
        )
        try {
            partRef.set(payload).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, partRef.path)
            throw e
        }
    }

    suspend fun updateParticipantMedia(
        roomId: String,
        isAudioMuted: Boolean,
        isVideoOff: Boolean
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val partRef = db.collection("rooms").document(roomId).collection("participants").document(uid)
        try {
            partRef.update(
                mapOf(
                    "isAudioMuted" to isAudioMuted,
                    "isVideoOff" to isVideoOff,
                    "lastSeen" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, partRef.path)
            throw e
        }
    }

    suspend fun leaveRoom(roomId: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        val partRef = db.collection("rooms").document(roomId).collection("participants").document(uid)
        try {
            partRef.delete().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, partRef.path)
            throw e
        }
    }

    fun observeParticipants(roomId: String): Flow<List<RoomParticipant>> = flow {
        val colRef = db.collection("rooms").document(roomId).collection("participants")
        emitAll(
            colRef.snapshots()
                .map { snapshot -> snapshot.toObjects(RoomParticipant::class.java) }
                .catch { error ->
                    if (error is Exception) handleFirestoreError(error, OperationType.LIST, colRef.path)
                    throw error
                }
        )
    }

    // --- Signaling ---

    suspend fun sendSignal(
        roomId: String,
        toUserId: String,
        type: String,
        payload: String
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val signalRef = db.collection("rooms").document(roomId).collection("signals").document()
        val data = mapOf(
            "fromUserId" to uid,
            "toUserId" to toUserId,
            "type" to type,
            "payload" to payload,
            "createdAt" to FieldValue.serverTimestamp()
        )
        try {
            signalRef.set(data).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, signalRef.path)
            throw e
        }
    }

    fun observeSignals(roomId: String): Flow<List<SignalMessage>> = flow {
        val uid = requireUserId()
        val colRef = db.collection("rooms").document(roomId).collection("signals")
        emitAll(
            colRef.orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(50)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(SignalMessage::class.java).filter {
                        it.toUserId == uid || it.toUserId == "broadcast" || it.fromUserId == uid
                    }
                }
                .catch { error ->
                    if (error is Exception) handleFirestoreError(error, OperationType.LIST, colRef.path)
                    throw error
                }
        )
    }

    // --- Chat Messages ---

    suspend fun sendChatMessage(roomId: String, text: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        val msgId = UUID.randomUUID().toString()
        val msgRef = db.collection("rooms").document(roomId).collection("messages").document(msgId)
        val payload = mapOf(
            "id" to msgId,
            "roomId" to roomId,
            "senderId" to uid,
            "senderName" to currentUserDisplayName(),
            "text" to text.trim(),
            "timestamp" to FieldValue.serverTimestamp()
        )
        try {
            msgRef.set(payload).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, msgRef.path)
            throw e
        }
    }

    fun observeMessages(roomId: String): Flow<List<ChatMessage>> = flow {
        val colRef = db.collection("rooms").document(roomId).collection("messages")
        emitAll(
            colRef.orderBy("timestamp", Query.Direction.ASCENDING)
                .snapshots()
                .map { snapshot -> snapshot.toObjects(ChatMessage::class.java) }
                .catch { error ->
                    if (error is Exception) handleFirestoreError(error, OperationType.LIST, colRef.path)
                    throw error
                }
        )
    }

    // --- Meeting History ---

    suspend fun recordMeetingHistory(
        roomId: String,
        title: String,
        durationMinutes: Long
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val historyId = UUID.randomUUID().toString()
        val historyRef = db.collection("users").document(uid).collection("meetings").document(historyId)
        val payload = mapOf(
            "userId" to uid,
            "roomId" to roomId,
            "title" to title.ifBlank { "Meeting" },
            "joinedAt" to FieldValue.serverTimestamp(),
            "durationMinutes" to durationMinutes
        )
        try {
            historyRef.set(payload).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, historyRef.path)
            throw e
        }
    }

    fun observeMeetingHistory(): Flow<List<MeetingHistoryItem>> = flow {
        val uid = requireUserId()
        val colRef = db.collection("users").document(uid).collection("meetings")
        emitAll(
            colRef.orderBy("joinedAt", Query.Direction.DESCENDING)
                .limit(20)
                .snapshots()
                .map { snapshot -> snapshot.toObjects(MeetingHistoryItem::class.java) }
                .catch { error ->
                    if (error is Exception) handleFirestoreError(error, OperationType.LIST, colRef.path)
                    throw error
                }
        )
    }
}
