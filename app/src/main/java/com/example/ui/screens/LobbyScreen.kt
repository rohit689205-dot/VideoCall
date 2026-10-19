package com.example.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.R
import com.example.media.CameraManager
import com.example.ui.AppScreen
import com.example.ui.CallViewModel
import com.example.ui.theme.ActiveGreen
import com.example.ui.theme.BorderNavy
import com.example.ui.theme.CallRed
import com.example.ui.theme.CardNavy
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.MicActiveYellow
import com.example.ui.theme.MidnightNavy
import com.example.ui.theme.RoyalViolet
import com.example.ui.theme.SlateNavy
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VibrantCyan
import com.google.firebase.Firebase
import com.google.firebase.auth.auth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LobbyScreen(
    viewModel: CallViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val currentUser = Firebase.auth.currentUser

    BackHandler {
        viewModel.navigateTo(AppScreen.DASHBOARD)
    }

    // CameraManager setup
    val cameraManager = remember { CameraManager(context) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

    // Permissions check
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasCameraPermission = permissions[Manifest.permission.CAMERA] ?: false
        hasAudioPermission = permissions[Manifest.permission.RECORD_AUDIO] ?: false

        if (hasAudioPermission) {
            cameraManager.startAudioMonitoring(coroutineScope)
        }
        val pv = previewViewRef
        if (hasCameraPermission && pv != null) {
            cameraManager.bindCamera(lifecycleOwner, pv)
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission || !hasAudioPermission) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        } else {
            cameraManager.startAudioMonitoring(coroutineScope)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraManager.release()
        }
    }

    val isCameraActive by cameraManager.isCameraActive.collectAsState()
    val isMicMuted by cameraManager.isMicMuted.collectAsState()
    val audioLevel by cameraManager.audioLevel.collectAsState()
    val animatedLevel by animateFloatAsState(targetValue = audioLevel, label = "audioLevel")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Meeting Lobby",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = { viewModel.navigateTo(AppScreen.DASHBOARD) },
                        modifier = Modifier.testTag("lobby_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to dashboard",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MidnightNavy)
            )
        },
        containerColor = MidnightNavy,
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Meeting ID and Title header card
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateNavy),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = uiState.currentRoom?.title ?: "Instant Meeting",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = uiState.targetRoomId,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = VibrantCyan
                        )
                    }
                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Meeting Code", uiState.targetRoomId)
                            clipboard.setPrimaryClip(clip)
                            viewModel.notifyInviteCopied()
                            Toast.makeText(context, "Room code copied to clipboard!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CardNavy),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("copy_room_code_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            tint = VibrantCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (uiState.inviteCopiedNotice) "Copied!" else "Copy",
                            fontSize = 12.sp,
                            color = VibrantCyan
                        )
                    }
                }
            }

            // Live CameraX Preview Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.Black),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(24.dp))
                    .border(2.dp, BorderNavy, RoundedCornerShape(24.dp))
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (hasCameraPermission && isCameraActive) {
                        AndroidView(
                            factory = { ctx ->
                                PreviewView(ctx).also { pv ->
                                    previewViewRef = pv
                                    cameraManager.bindCamera(lifecycleOwner, pv)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // Camera Off / Permission Required placeholder
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(CardNavy),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    modifier = Modifier
                                        .size(80.dp)
                                        .clip(CircleShape)
                                        .background(RoyalViolet.copy(alpha = 0.3f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(48.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = if (!hasCameraPermission) "Camera permission required" else "Camera is turned off",
                                    fontSize = 14.sp,
                                    color = TextSecondary
                                )
                            }
                        }
                    }

                    // Floating Controls Pill inside Camera View
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 20.dp)
                            .clip(RoundedCornerShape(30.dp))
                            .background(Color.Black.copy(alpha = 0.65f))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Mic Button
                        IconButton(
                            onClick = {
                                cameraManager.toggleMic()
                                viewModel.toggleLobbyMic()
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(if (isMicMuted) CallRed else CardNavy)
                                .testTag("lobby_mic_toggle")
                        ) {
                            Icon(
                                imageVector = if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = if (isMicMuted) "Unmute mic" else "Mute mic",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Video Button
                        IconButton(
                            onClick = {
                                previewViewRef?.let { pv ->
                                    cameraManager.toggleCamera(lifecycleOwner, pv)
                                }
                                viewModel.toggleLobbyVideo()
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(if (!isCameraActive) CallRed else CardNavy)
                                .testTag("lobby_video_toggle")
                        ) {
                            Icon(
                                imageVector = if (isCameraActive) Icons.Default.Videocam else Icons.Default.VideocamOff,
                                contentDescription = if (isCameraActive) "Turn camera off" else "Turn camera on",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Flip Lens Button
                        IconButton(
                            onClick = {
                                previewViewRef?.let { pv ->
                                    cameraManager.switchLens(lifecycleOwner, pv)
                                }
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(CardNavy)
                                .testTag("lobby_switch_lens_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cameraswitch,
                                contentDescription = "Switch front/back camera",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Audio level meter indicator at top-left
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(16.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isMicMuted) CallRed else if (animatedLevel > 0.05f) MicActiveYellow else ActiveGreen)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isMicMuted) "Muted" else if (animatedLevel > 0.1f) "Speaking" else "Mic ready",
                            fontSize = 11.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Bottom CTA Card: "Join Now"
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Joining as ${if (uiState.isGuestMode) "Guest" else (currentUser?.displayName ?: "User")}",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        viewModel.joinCallFromLobby()
                    },
                    enabled = !uiState.isLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricViolet),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("join_now_button")
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.join_now),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
