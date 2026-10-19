package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val email: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class RoomSession(
    @DocumentId val id: String = "",
    val hostId: String = "",
    val hostName: String = "",
    val title: String = "",
    val status: String = "active", // "waiting", "active", "ended"
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class RoomParticipant(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val isAudioMuted: Boolean = false,
    val isVideoOff: Boolean = false,
    val isHost: Boolean = false,
    @ServerTimestamp val joinedAt: Timestamp? = null,
    @ServerTimestamp val lastSeen: Timestamp? = null
)

@IgnoreExtraProperties
data class SignalMessage(
    @DocumentId val id: String = "",
    val fromUserId: String = "",
    val toUserId: String = "",
    val type: String = "heartbeat", // offer, answer, candidate, leave, heartbeat
    val payload: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ChatMessage(
    @DocumentId val id: String = "",
    val roomId: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val text: String = "",
    @ServerTimestamp val timestamp: Timestamp? = null
)

@IgnoreExtraProperties
data class MeetingHistoryItem(
    @DocumentId val id: String = "",
    val userId: String = "",
    val roomId: String = "",
    val title: String = "",
    @ServerTimestamp val joinedAt: Timestamp? = null,
    val durationMinutes: Long = 0L
)
