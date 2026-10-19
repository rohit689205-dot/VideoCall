package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.AppScreen
import com.example.ui.CallViewModel
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.LiveCallScreen
import com.example.ui.screens.LobbyScreen
import com.example.ui.screens.MeetingEndedScreen
import com.example.ui.screens.ProfileScreen
import com.example.ui.theme.MidnightNavy
import com.example.ui.theme.VideoCallTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VideoCallTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MidnightNavy
                ) {
                    AppContent()
                }
            }
        }
    }
}

@Composable
fun AppContent(
    callViewModel: CallViewModel = viewModel()
) {
    var currentUser by remember { mutableStateOf(Firebase.auth.currentUser) }
    val uiState by callViewModel.uiState.collectAsState()

    DisposableEffect(Unit) {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            currentUser = auth.currentUser
            if (auth.currentUser != null) {
                callViewModel.setGuestMode(false)
            }
        }
        Firebase.auth.addAuthStateListener(listener)
        onDispose {
            Firebase.auth.removeAuthStateListener(listener)
        }
    }

    if (currentUser == null && !uiState.isGuestMode) {
        AuthScreen(
            onAuthSuccess = {
                currentUser = Firebase.auth.currentUser
                callViewModel.setGuestMode(false)
            },
            onContinueAsGuest = {
                callViewModel.setGuestMode(true)
            }
        )
    } else {
        MainAppNavigation(
            callViewModel = callViewModel,
            onSignOut = {
                currentUser = null
                callViewModel.setGuestMode(false)
                callViewModel.navigateTo(AppScreen.DASHBOARD)
            }
        )
    }
}

@Composable
fun MainAppNavigation(
    onSignOut: () -> Unit,
    callViewModel: CallViewModel = viewModel()
) {
    val uiState by callViewModel.uiState.collectAsState()

    AnimatedContent(
        targetState = uiState.currentScreen,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "ScreenTransition"
    ) { screen ->
        when (screen) {
            AppScreen.WELCOME,
            AppScreen.AUTH,
            AppScreen.DASHBOARD -> {
                DashboardScreen(viewModel = callViewModel)
            }
            AppScreen.CREATE_ROOM,
            AppScreen.JOIN_ROOM,
            AppScreen.LOBBY -> {
                LobbyScreen(viewModel = callViewModel)
            }
            AppScreen.LIVE_CALL -> {
                LiveCallScreen(viewModel = callViewModel)
            }
            AppScreen.MEETING_ENDED -> {
                MeetingEndedScreen(viewModel = callViewModel)
            }
            AppScreen.PROFILE -> {
                ProfileScreen(
                    viewModel = callViewModel,
                    onSignOut = onSignOut
                )
            }
        }
    }
}
