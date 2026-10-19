package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.ChatMessage
import com.example.data.model.MeetingHistoryItem
import com.example.data.model.RoomParticipant
import com.example.data.model.RoomSession
import com.example.data.repository.CallRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class AppScreen {
    WELCOME,
    AUTH,
    DASHBOARD,
    CREATE_ROOM,
    JOIN_ROOM,
    LOBBY,
    LIVE_CALL,
    MEETING_ENDED,
    PROFILE
}

data class MeetingEndedSummary(
    val roomId: String,
    val title: String,
    val durationSeconds: Long,
    val participantCount: Int
)

data class CallUiState(
    val currentScreen: AppScreen = AppScreen.DASHBOARD,
    val targetRoomId: String = "",
    val roomTitleInput: String = "",
    val currentRoom: RoomSession? = null,
    val participants: List<RoomParticipant> = emptyList(),
    val chatMessages: List<ChatMessage> = emptyList(),
    val meetingHistory: List<MeetingHistoryItem> = emptyList(),
    val isMicMuted: Boolean = false,
    val isVideoOff: Boolean = false,
    val isScreenSharing: Boolean = false,
    val screenSharePresenterName: String? = null,
    val isRecording: Boolean = false,
    val recordingDurationSeconds: Long = 0L,
    val lastSavedRecordingUri: String? = null,
    val recordingNotice: String? = null,
    val isScreenRecording: Boolean = false,
    val screenRecordingDurationSeconds: Long = 0L,
    val lastSavedScreenRecordingUri: String? = null,
    val screenRecordingNotice: String? = null,
    val callDurationSeconds: Long = 0L,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isChatDrawerOpen: Boolean = false,
    val isParticipantListOpen: Boolean = false,
    val inviteCopiedNotice: Boolean = false,
    val isGuestMode: Boolean = false,
    val endedSummary: MeetingEndedSummary? = null
)

class CallViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = CallRepository(application.applicationContext)

    private val _uiState = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    private var participantsJob: Job? = null
    private var chatJob: Job? = null
    private var roomJob: Job? = null
    private var signalsJob: Job? = null
    private var timerJob: Job? = null
    private var callStartTime: Long = 0L

    init {
        observeHistory()
        observeScreenRecordingService()
    }

    private fun observeScreenRecordingService() {
        viewModelScope.launch {
            com.example.service.ScreenRecordState.isRecording.collect { recording ->
                _uiState.update { it.copy(isScreenRecording = recording) }
            }
        }
        viewModelScope.launch {
            com.example.service.ScreenRecordState.durationSeconds.collect { secs ->
                _uiState.update { it.copy(screenRecordingDurationSeconds = secs) }
            }
        }
        viewModelScope.launch {
            com.example.service.ScreenRecordState.lastSavedUri.collect { uri ->
                if (uri != null) {
                    _uiState.update {
                        it.copy(
                            lastSavedScreenRecordingUri = uri,
                            screenRecordingNotice = "Screen recording saved to Movies/VideoCall"
                        )
                    }
                    delay(6000)
                    _uiState.update { it.copy(screenRecordingNotice = null) }
                }
            }
        }
        viewModelScope.launch {
            com.example.service.ScreenRecordState.errorMessage.collect { err ->
                if (err != null) {
                    _uiState.update { it.copy(errorMessage = err) }
                }
            }
        }
    }

    fun dismissScreenRecordingNotice() {
        _uiState.update { it.copy(screenRecordingNotice = null) }
    }

    fun setRecordingActive(recording: Boolean) {
        _uiState.update { it.copy(isRecording = recording) }
    }

    fun setRecordingDuration(seconds: Long) {
        _uiState.update { it.copy(recordingDurationSeconds = seconds) }
    }

    fun onRecordingSaved(uriString: String?) {
        _uiState.update {
            it.copy(
                isRecording = false,
                lastSavedRecordingUri = uriString,
                recordingNotice = "Call recording saved to Movies/VideoCall"
            )
        }
        viewModelScope.launch {
            delay(5000)
            _uiState.update { it.copy(recordingNotice = null) }
        }
    }

    fun dismissRecordingNotice() {
        _uiState.update { it.copy(recordingNotice = null) }
    }

    fun setScreenSharing(sharing: Boolean, presenterName: String = "You") {
        _uiState.update {
            it.copy(
                isScreenSharing = sharing,
                screenSharePresenterName = if (sharing) presenterName else null
            )
        }
        val room = _uiState.value.currentRoom ?: return
        if (!_uiState.value.isGuestMode) {
            viewModelScope.launch {
                val payload = if (sharing) "screenshare:active:$presenterName" else "screenshare:stopped"
                repository.sendSignal(room.id, "broadcast", "candidate", payload)
            }
        }
    }

    fun setGuestMode(isGuest: Boolean) {
        _uiState.update { it.copy(isGuestMode = isGuest) }
    }

    fun navigateTo(screen: AppScreen) {
        _uiState.update { it.copy(currentScreen = screen, errorMessage = null) }
    }

    fun setTargetRoomId(code: String) {
        _uiState.update { it.copy(targetRoomId = code.trim()) }
    }

    fun setRoomTitleInput(title: String) {
        _uiState.update { it.copy(roomTitleInput = title) }
    }

    fun toggleLobbyMic() {
        _uiState.update { it.copy(isMicMuted = !it.isMicMuted) }
    }

    fun toggleLobbyVideo() {
        _uiState.update { it.copy(isVideoOff = !it.isVideoOff) }
    }

    fun toggleInCallMic() {
        val newMuted = !_uiState.value.isMicMuted
        _uiState.update { it.copy(isMicMuted = newMuted) }
        val roomId = _uiState.value.currentRoom?.id ?: return
        viewModelScope.launch {
            repository.updateParticipantMedia(roomId, newMuted, _uiState.value.isVideoOff)
        }
    }

    fun toggleInCallVideo() {
        val newVideoOff = !_uiState.value.isVideoOff
        _uiState.update { it.copy(isVideoOff = newVideoOff) }
        val roomId = _uiState.value.currentRoom?.id ?: return
        viewModelScope.launch {
            repository.updateParticipantMedia(roomId, _uiState.value.isMicMuted, newVideoOff)
        }
    }

    fun toggleScreenSharing() {
        _uiState.update { it.copy(isScreenSharing = !it.isScreenSharing) }
    }

    fun toggleChatDrawer() {
        _uiState.update { it.copy(isChatDrawerOpen = !it.isChatDrawerOpen, isParticipantListOpen = false) }
    }

    fun toggleParticipantList() {
        _uiState.update { it.copy(isParticipantListOpen = !it.isParticipantListOpen, isChatDrawerOpen = false) }
    }

    fun notifyInviteCopied() {
        viewModelScope.launch {
            _uiState.update { it.copy(inviteCopiedNotice = true) }
            delay(2500)
            _uiState.update { it.copy(inviteCopiedNotice = false) }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // --- Create Room & Prepare Lobby ---

    fun createInstantMeeting(title: String = "") {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val roomTitle = title.ifBlank { "Instant Meeting" }

            if (_uiState.value.isGuestMode) {
                val randomSuffix = (100000..999999).random()
                val roomId = "vid-${randomSuffix.toString().substring(0, 3)}-${randomSuffix.toString().substring(3)}"
                val room = RoomSession(
                    id = roomId,
                    hostId = "guest_user",
                    hostName = "Guest Host",
                    title = roomTitle,
                    status = "active"
                )
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        targetRoomId = room.id,
                        currentRoom = room,
                        currentScreen = AppScreen.LOBBY
                    )
                }
                return@launch
            }

            val result = repository.createRoom(roomTitle)
            result.onSuccess { room ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        targetRoomId = room.id,
                        currentRoom = room,
                        currentScreen = AppScreen.LOBBY
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = err.localizedMessage ?: "Failed to create meeting")
                }
            }
        }
    }

    // --- Prepare Join from Code ---

    fun prepareJoinRoom(roomIdInput: String) {
        val cleanId = roomIdInput.trim()
        if (cleanId.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please enter a valid room code.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            if (_uiState.value.isGuestMode) {
                val room = RoomSession(
                    id = cleanId,
                    hostId = "host",
                    hostName = "Host",
                    title = "Video Call",
                    status = "active"
                )
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        targetRoomId = cleanId,
                        currentRoom = room,
                        currentScreen = AppScreen.LOBBY
                    )
                }
                return@launch
            }

            val result = repository.getRoom(cleanId)
            result.onSuccess { room ->
                if (room != null && room.status != "ended") {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            targetRoomId = cleanId,
                            currentRoom = room,
                            currentScreen = AppScreen.LOBBY
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = if (room?.status == "ended") "This meeting has ended." else "Meeting not found. Please check room code."
                        )
                    }
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = err.localizedMessage ?: "Failed to find room")
                }
            }
        }
    }

    // --- Join Call from Lobby ---

    fun joinCallFromLobby() {
        val room = _uiState.value.currentRoom ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            if (_uiState.value.isGuestMode) {
                val guestParticipant = RoomParticipant(
                    userId = "guest_user",
                    displayName = "Guest User",
                    isAudioMuted = _uiState.value.isMicMuted,
                    isVideoOff = _uiState.value.isVideoOff,
                    isHost = true
                )
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        currentScreen = AppScreen.LIVE_CALL,
                        callDurationSeconds = 0L,
                        participants = listOf(guestParticipant),
                        isChatDrawerOpen = false,
                        isParticipantListOpen = false
                    )
                }
                startCallObservables(room.id)
                return@launch
            }

            val isHost = room.hostId == (repository.currentUserIdOrNull() ?: "")

            val joinResult = repository.joinRoom(
                roomId = room.id,
                displayName = repository.currentUserDisplayName(),
                isHost = isHost,
                isAudioMuted = _uiState.value.isMicMuted,
                isVideoOff = _uiState.value.isVideoOff
            )

            joinResult.onSuccess {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        currentScreen = AppScreen.LIVE_CALL,
                        callDurationSeconds = 0L,
                        isChatDrawerOpen = false,
                        isParticipantListOpen = false
                    )
                }
                startCallObservables(room.id)
            }.onFailure { err ->
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = err.localizedMessage ?: "Failed to join room")
                }
            }
        }
    }

    private fun startCallObservables(roomId: String) {
        callStartTime = System.currentTimeMillis()

        // Timer
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                val elapsed = (System.currentTimeMillis() - callStartTime) / 1000
                _uiState.update { it.copy(callDurationSeconds = elapsed) }
            }
        }

        // Room status listener
        roomJob?.cancel()
        roomJob = viewModelScope.launch {
            repository.observeRoom(roomId)
                .catch { /* error handled in repo */ }
                .collect { updatedRoom ->
                    if (updatedRoom != null) {
                        _uiState.update { it.copy(currentRoom = updatedRoom) }
                        if (updatedRoom.status == "ended") {
                            leaveCall(isHostEnding = false)
                        }
                    }
                }
        }

        // Participants listener
        participantsJob?.cancel()
        participantsJob = viewModelScope.launch {
            repository.observeParticipants(roomId)
                .catch { /* handled */ }
                .collect { list ->
                    _uiState.update { it.copy(participants = list) }
                }
        }

        // Chat messages listener
        chatJob?.cancel()
        chatJob = viewModelScope.launch {
            repository.observeMessages(roomId)
                .catch { /* handled */ }
                .collect { msgs ->
                    _uiState.update { it.copy(chatMessages = msgs) }
                }
        }

        // Signals listener for live screen share broadcast
        signalsJob?.cancel()
        signalsJob = viewModelScope.launch {
            if (!_uiState.value.isGuestMode) {
                repository.observeSignals(roomId)
                    .catch { /* handled */ }
                    .collect { signals ->
                        signals.firstOrNull()?.let { latest ->
                            if (latest.payload.startsWith("screenshare:active:")) {
                                val name = latest.payload.removePrefix("screenshare:active:")
                                _uiState.update {
                                    it.copy(
                                        isScreenSharing = true,
                                        screenSharePresenterName = if (latest.fromUserId == repository.currentUserIdOrNull()) "You" else name
                                    )
                                }
                            } else if (latest.payload == "screenshare:stopped") {
                                _uiState.update {
                                    it.copy(
                                        isScreenSharing = false,
                                        screenSharePresenterName = null
                                    )
                                }
                            }
                        }
                    }
            }
        }
    }

    fun sendChatMessage(text: String) {
        val roomId = _uiState.value.currentRoom?.id ?: return
        if (text.isBlank()) return
        if (_uiState.value.isGuestMode) {
            val localMsg = ChatMessage(
                id = java.util.UUID.randomUUID().toString(),
                roomId = roomId,
                senderId = "guest_user",
                senderName = "Guest",
                text = text.trim()
            )
            _uiState.update { it.copy(chatMessages = it.chatMessages + localMsg) }
            return
        }
        viewModelScope.launch {
            repository.sendChatMessage(roomId, text)
        }
    }

    fun leaveCall(isHostEnding: Boolean = false) {
        val room = _uiState.value.currentRoom
        val duration = _uiState.value.callDurationSeconds
        val pCount = _uiState.value.participants.size

        // Stop background call jobs
        timerJob?.cancel()
        participantsJob?.cancel()
        chatJob?.cancel()
        roomJob?.cancel()
        signalsJob?.cancel()

        if (room != null && !_uiState.value.isGuestMode && repository.currentUserIdOrNull() != null) {
            viewModelScope.launch {
                val durationMinutes = (duration / 60).coerceAtLeast(1)
                repository.recordMeetingHistory(room.id, room.title, durationMinutes)
                if (isHostEnding && room.hostId == (repository.currentUserIdOrNull() ?: "")) {
                    repository.endRoom(room.id)
                }
                repository.leaveRoom(room.id)
            }
        }

        _uiState.update {
            it.copy(
                currentScreen = AppScreen.MEETING_ENDED,
                endedSummary = MeetingEndedSummary(
                    roomId = room?.id ?: "",
                    title = room?.title ?: "Meeting",
                    durationSeconds = duration,
                    participantCount = pCount.coerceAtLeast(1)
                ),
                isChatDrawerOpen = false,
                isParticipantListOpen = false,
                isScreenSharing = false
            )
        }
    }

    private fun observeHistory() {
        viewModelScope.launch {
            try {
                if (repository.currentUserIdOrNull() != null) {
                    repository.observeMeetingHistory()
                        .catch { /* error logged */ }
                        .collect { history ->
                            _uiState.update { it.copy(meetingHistory = history) }
                        }
                }
            } catch (_: Exception) {}
        }
    }

    fun refreshHistory() {
        observeHistory()
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
        participantsJob?.cancel()
        chatJob?.cancel()
        roomJob?.cancel()
        signalsJob?.cancel()
    }
}
